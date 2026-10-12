// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import io.github.miam1ku.mibandoplusbridge.data.HealthRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个睡眠日上的每段手环会话都留下。行按睡眠协议版本 11 写：
 * 间隔超过 20 分钟的后一段单独成卡，短于 120 分钟的是小睡。
 */
public final class OHealthSleepPlan {
    /** 宿主 Sleep.sleepState。映射把 2、3、4 收成深睡、REM、浅睡，其余状态收成清醒。 */
    public static final int DEEP = 2;
    public static final int REM = 3;
    public static final int LIGHT = 4;
    /** 官方的短清醒。睡眠状态值是 5。图表类型 4 是另一套编号，和这个常量不是一回事。这段时间不计入睡眠分钟。 */
    public static final int AWAKE = 5;

    public record Segment(long startMs, long endMs, int sleepState) {}

    public record Night(int date, ZoneId zone, long fallAsleepMs, long wakeMs, long sleepMinutes,
            long deepMinutes, long lightMinutes, long remMinutes, long wakeMinutes,
            List<Segment> segments, long dayStartMs, long dayEndMs) {
        public Night(int date, long fallAsleepMs, long wakeMs, long sleepMinutes, long deepMinutes,
                long lightMinutes, long remMinutes, long wakeMinutes, List<Segment> segments,
                long dayStartMs, long dayEndMs) {
            this(date, ZoneId.systemDefault(), fallAsleepMs, wakeMs, sleepMinutes, deepMinutes,
                    lightMinutes, remMinutes, wakeMinutes, segments, dayStartMs, dayEndMs);
        }
    }

    public record Day(int date, ZoneId zone, long fallAsleepMs, long wakeMs, long sleepMinutes,
            long deepMinutes, long lightMinutes, long remMinutes, long wakeMinutes,
            List<Night> nights, Night mainSession, long dayStartMs, long dayEndMs) {}

    private OHealthSleepPlan() {}

    /** 醒来时刻落在哪个 20:00 到次日 20:00 的窗口，就记哪一天。 */
    public static int sleepDate(long endMs, ZoneId zone) {
        var local = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDateTime();
        LocalDate day = local.getHour() >= 20 ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        return day.getYear() * 10000 + day.getMonthValue() * 100 + day.getDayOfMonth();
    }

    /**
     * 健康把睡眠小结的钟点存成睡眠日分钟，和宿主 StoreUtil.changeMillisToCurrentDayMinutes 一样：
     * 20:00 是 1200，01:39 是 1539。公开插入接口原样收下这个数，所以桥接在写入前换算。
     */
    public static long sleepDayMinutes(long epochMs, ZoneId zone) {
        var local = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime();
        int hour = local.getHour();
        if (hour < 20) hour += 24;
        return hour * 60L + local.getMinute();
    }

    /** 手环阶段 2 深睡、3 浅睡、4 REM、5 清醒。宿主没有单独的「熟睡」(deep sleep) 档。 */
    public static int hostState(int bandStage) {
        return switch (bandStage) {
            case 2 -> DEEP;
            case 3 -> LIGHT;
            case 4 -> REM;
            case 5 -> AWAKE;
            default -> throw new IllegalArgumentException("UNSUPPORTED_SLEEP_STAGE");
        };
    }

    /**
     * 20:00 之前开始的会话，把自己的清空窗口拉回到这个起点。同一天的另一段会话不跟着拉。
     * 没有阶段的区间画成一条浅睡。这条浅睡不记深睡或 REM。挤在同一分钟里的阶段裁成一段，深睡和浅睡不叠在一起。
     */
    public static List<Night> nights(List<HealthRecord> records) {
        List<HealthRecord> intervals = new ArrayList<>();
        List<HealthRecord> stages = new ArrayList<>();
        for (HealthRecord record : records) {
            if ("sleep_interval".equals(record.kind)) intervals.add(record);
            else if ("sleep_stage".equals(record.kind)) stages.add(record);
        }
        intervals.sort(Comparator.comparingLong(record -> record.startMs));
        boolean[] used = new boolean[stages.size()];
        List<Session> sessions = new ArrayList<>();
        for (HealthRecord interval : intervals) {
            List<Segment> segments = new ArrayList<>();
            for (int i = 0; i < stages.size(); i++) {
                if (used[i]) continue;
                HealthRecord stage = stages.get(i);
                long overlap = overlap(interval, stage);
                if (overlap <= 0 || !bestInterval(interval, stage, intervals, overlap)) continue;
                used[i] = true;
                segments.add(new Segment(stage.startMs, stage.endMs, hostState(stage.stage)));
            }
            if (segments.isEmpty()) {
                segments.add(new Segment(interval.startMs, interval.endMs, LIGHT));
            }
            segments = flatten(segments);
            ZoneId zone = interval.timezone == null ? ZoneId.systemDefault() : ZoneId.of(interval.timezone);
            List<List<Segment>> clusters = new ArrayList<>();
            List<Segment> currentCluster = new ArrayList<>();
            currentCluster.add(segments.get(0));
            for (int i = 1; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                Segment prev = segments.get(i - 1);
                if (seg.startMs() - prev.endMs() > 1_200_000L) {
                    clusters.add(currentCluster);
                    currentCluster = new ArrayList<>();
                }
                currentCluster.add(seg);
            }
            clusters.add(currentCluster);

            for (List<Segment> cluster : clusters) {
                long fall = cluster.get(0).startMs();
                long wake = cluster.get(cluster.size() - 1).endMs();
                int date = sleepDate(wake, zone);
                Count counted = count(cluster);
                sessions.add(new Session(date, zone, fall, wake,
                        counted.sleep, counted.deep, counted.light, counted.rem, counted.awake, cluster));
            }
        }
        List<Night> nights = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            long[] window = sleepDayWindow(session.date, session.zone);
            long purgeStart = Math.min(window[0], session.fall);
            List<Segment> flat = flatten(session.segments);
            Count counted = count(flat);
            nights.add(new Night(session.date, session.zone, session.fall, session.wake, counted.sleep, counted.deep,
                    counted.light, counted.rem, counted.awake, List.copyOf(flat),
                    purgeStart, window[1]));
        }
        nights.sort(Comparator.comparingInt(Night::date).thenComparingLong(Night::fallAsleepMs));
        return nights;
    }

    /**
     * 把会话收成睡眠日。一天的小结是各段之和，每段仍留碎片。
     * 主会话取至少 120 分钟里最长的那段。
     */
    public static List<Day> days(List<Night> nights) {
        if (nights == null || nights.isEmpty()) return List.of();
        Map<Integer, List<Night>> byDate = new LinkedHashMap<>();
        for (Night night : nights) {
            byDate.computeIfAbsent(night.date(), k -> new ArrayList<>()).add(night);
        }
        List<Day> days = new ArrayList<>();
        for (Map.Entry<Integer, List<Night>> entry : byDate.entrySet()) {
            int date = entry.getKey();
            List<Night> dateNights = entry.getValue();
            dateNights.sort(Comparator.comparingLong(Night::fallAsleepMs));

            long minFall = Long.MAX_VALUE;
            long maxWake = Long.MIN_VALUE;
            long totalSleep = 0;
            long totalDeep = 0;
            long totalLight = 0;
            long totalRem = 0;
            long totalWake = 0;
            long dayStart = Long.MAX_VALUE;
            long dayEnd = Long.MIN_VALUE;
            ZoneId zone = null;
            Night mainSession = null;

            for (Night night : dateNights) {
                minFall = Math.min(minFall, night.fallAsleepMs());
                maxWake = Math.max(maxWake, night.wakeMs());
                totalSleep += night.sleepMinutes();
                totalDeep += night.deepMinutes();
                totalLight += night.lightMinutes();
                totalRem += night.remMinutes();
                totalWake += night.wakeMinutes();
                dayStart = Math.min(dayStart, night.dayStartMs());
                dayEnd = Math.max(dayEnd, night.dayEndMs());
                if (zone == null) zone = night.zone();
                if (summary(nights, night)) {
                    mainSession = night;
                }
            }
            if (zone == null) zone = ZoneId.systemDefault();

            days.add(new Day(date, zone, minFall, maxWake, totalSleep, totalDeep, totalLight,
                    totalRem, totalWake, List.copyOf(dateNights), mainSession, dayStart, dayEnd));
        }
        days.sort(Comparator.comparingInt(Day::date));
        return days;
    }

    /**
     * 一天的小结是至少 120 分钟里最长的那段。更短的是小睡，替换不了这段小结。
     */
    public static boolean summary(List<Night> nights, Night night) {
        if (night.sleepMinutes() < 120) return false;
        for (Night other : nights) {
            if (other.date() != night.date() || other == night) continue;
            if (other.sleepMinutes() > night.sleepMinutes()) return false;
            if (other.sleepMinutes() == night.sleepMinutes()
                    && other.fallAsleepMs() < night.fallAsleepMs()) return false;
        }
        return true;
    }

    /** 这个睡眠日所属日历日的 [20:00, 次日 20:00)。 */
    static long[] sleepDayWindow(int date, ZoneId zone) {
        int year = date / 10000;
        int month = (date / 100) % 100;
        int day = date % 100;
        var end = LocalDate.of(year, month, day).atTime(20, 0).atZone(zone);
        return new long[] {end.minusDays(1).toInstant().toEpochMilli(), end.toInstant().toEpochMilli()};
    }

    private static boolean bestInterval(HealthRecord interval, HealthRecord stage, List<HealthRecord> intervals,
            long overlap) {
        for (HealthRecord other : intervals) {
            if (other == interval) continue;
            long otherOverlap = overlap(other, stage);
            if (otherOverlap > overlap || (otherOverlap == overlap && other.startMs < interval.startMs)) return false;
        }
        return true;
    }

    private static long overlap(HealthRecord interval, HealthRecord stage) {
        return Math.max(0, Math.min(interval.endMs, stage.endMs) - Math.max(interval.startMs, stage.startMs));
    }

    private static List<Segment> merge(List<Segment> segments) {
        if (segments.isEmpty()) return segments;
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(Segment::startMs).thenComparingInt(Segment::sleepState));
        List<Segment> merged = new ArrayList<>();
        Segment current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Segment next = sorted.get(i);
            if (next.sleepState == current.sleepState && next.startMs <= current.endMs) {
                current = new Segment(current.startMs, Math.max(current.endMs, next.endMs), current.sleepState);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * 同一时刻只留一行。后一段从前面一段里面开始时，只留下超出的尾巴。
     * 起点相同就留更长的那行，长度一样就留宿主状态值更小的。
     */
    private static List<Segment> flatten(List<Segment> segments) {
        if (segments.size() < 2) return segments;
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(Segment::startMs)
                .thenComparing((left, right) -> Long.compare(
                        right.endMs() - right.startMs(), left.endMs() - left.startMs()))
                .thenComparingInt(Segment::sleepState));
        List<Segment> parted = new ArrayList<>();
        Segment current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Segment next = sorted.get(i);
            if (next.startMs() < current.endMs()) {
                if (next.endMs() <= current.endMs()) continue;
                next = new Segment(current.endMs(), next.endMs(), next.sleepState());
            }
            parted.add(current);
            current = next;
        }
        parted.add(current);
        return stitch(merge(parted));
    }

    /**
     * 手表协议版本 11 在间隔不超过 20 分钟时仍算同一张卡。
     * 更短的缝按原边界接上，因为中间一有空洞就会新开一张卡。
     */
    private static List<Segment> stitch(List<Segment> segments) {
        if (segments.size() < 2) return segments;
        List<Segment> stitched = new ArrayList<>();
        Segment current = segments.get(0);
        for (int i = 1; i < segments.size(); i++) {
            Segment next = segments.get(i);
            long gap = next.startMs() - current.endMs();
            if (gap > 0 && gap <= 1_200_000L) {
                if (gap < 60_000L) {
                    current = new Segment(current.startMs(), next.startMs(), current.sleepState());
                } else {
                    stitched.add(current);
                    current = new Segment(current.endMs(), next.startMs(), AWAKE);
                }
            }
            stitched.add(current);
            current = next;
        }
        stitched.add(current);
        return stitched;
    }

    /** 清醒分钟留在 awake，不进 {@code sleep}。 */
    private static Count count(List<Segment> segments) {
        long sleep = 0, deep = 0, light = 0, rem = 0, awake = 0;
        for (Segment segment : segments) {
            long minutes = Math.max(0, (segment.endMs - segment.startMs) / 60_000);
            switch (segment.sleepState) {
                case DEEP -> { deep += minutes; sleep += minutes; }
                case LIGHT -> { light += minutes; sleep += minutes; }
                case REM -> { rem += minutes; sleep += minutes; }
                case AWAKE -> awake += minutes;
                default -> throw new IllegalStateException("SLEEP_STATE_UNMAPPED");
            }
        }
        return new Count(sleep, deep, light, rem, awake);
    }

    private record Count(long sleep, long deep, long light, long rem, long awake) {}

    private static final class Session {
        final int date;
        final ZoneId zone;
        final long fall, wake, sleep, deep, light, rem, awake;
        final List<Segment> segments;

        Session(int date, ZoneId zone, long fall, long wake, long sleep, long deep, long light, long rem,
                long awake, List<Segment> segments) {
            this.date = date;
            this.zone = zone;
            this.fall = fall;
            this.wake = wake;
            this.sleep = sleep;
            this.deep = deep;
            this.light = light;
            this.rem = rem;
            this.awake = awake;
            this.segments = segments;
        }
    }
}
