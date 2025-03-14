/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.audit.utils;

import org.apache.commons.lang.StringUtils;

import java.util.Calendar;
import java.util.Date;

public class RollingTimeUtil {
    public static final String MINUTES = "m"; //minutes
    public static final String HOURS   = "h"; //hours
    public static final String DAYS    = "d"; //days
    public static final String WEEKS   = "w"; //weeks
    public static final String MONTHS  = "M"; //months
    public static final String YEARS   = "y"; //years

    private static final int SECONDS_IN_MINUTE = 60;
    private static final int SECONDS_IN_HOUR   = 60 * SECONDS_IN_MINUTE;
    private static final int SECONDS_IN_DAY    = 24 * SECONDS_IN_HOUR;

    private static volatile RollingTimeUtil me;

    public RollingTimeUtil() {
    }

    public static RollingTimeUtil getInstance() {
        RollingTimeUtil result = me;

        if (result == null) {
            synchronized (RollingTimeUtil.class) {
                result = me;

                if (result == null) {
                    result = new RollingTimeUtil();
                    me     = result;
                }
            }
        }

        return result;
    }

    public static void main(String[] args) {
        // Test Method for RolloverTime calculation
        // Set rollOverPeriod 10m,30m..,1h,2h,..1d,2d..,1w,2w..,1M,2M..1y..2y
        // If nothing is set for rollOverPeriod or Duration default rollOverPeriod is 1 day
        String          rollOverPeriod   = "";
        RollingTimeUtil rollingTimeUtil  = new RollingTimeUtil();
        int             duration         = 86400;
        Date            nextRollOvertime;

        try {
            nextRollOvertime = rollingTimeUtil.computeNextRollingTime(rollOverPeriod);
        } catch (Exception e) {
            rollOverPeriod = rollingTimeUtil.convertRolloverSecondsToRolloverPeriod(duration);

            System.out.println(rollOverPeriod);

            try {
                nextRollOvertime = rollingTimeUtil.computeNextRollingTime(rollOverPeriod);

                System.out.println(nextRollOvertime);
            } catch (Exception e1) {
                e1.printStackTrace();
            }

            long rollOverTime = rollingTimeUtil.computeNextRollingTime(duration, null);

            nextRollOvertime = new Date(rollOverTime);
        }

        System.out.println(nextRollOvertime);
    }

    public Date computeNextRollingTime(String rollingTimePeriod) throws Exception {
        final Date ret;

        if (!StringUtils.isEmpty(rollingTimePeriod)) {
            String computePeriod = getTimeLiteral(rollingTimePeriod);
            int    timeNumeral   = getTimeNumeral(rollingTimePeriod, computePeriod);

            switch (computePeriod) {
                case MINUTES:
                    ret = computeTopOfMinuteDate(timeNumeral);
                    break;
                case HOURS:
                    ret = computeTopOfHourDate(timeNumeral);
                    break;
                case DAYS:
                    ret = computeTopOfDayDate(timeNumeral);
                    break;
                case WEEKS:
                    ret = computeTopOfWeekDate(timeNumeral);
                    break;
                case MONTHS:
                    ret = computeTopofMonthDate(timeNumeral);
                    break;
                case YEARS:
                    ret = computeTopOfYearDate(timeNumeral);
                    break;
                default:
                    ret = null;
                    break;
            }
        } else {
            throw new Exception("Unable to compute Next Rolling using the given Rollover period");
        }

        return ret;
    }

    public String convertRolloverSecondsToRolloverPeriod(long duration) {
        int days = (int) (duration / SECONDS_IN_DAY);

        duration %= SECONDS_IN_DAY;

        int hours = (int) (duration / SECONDS_IN_HOUR);

        duration %= SECONDS_IN_HOUR;

        int minutes = (int) (duration / SECONDS_IN_MINUTE);

        String ret = null;

        if (days != 0) {
            if (hours == 0 && minutes == 0) {
                ret = (days + DAYS);
            }
        } else if (hours != 0) {
            if (minutes == 0) {
                ret = (hours + HOURS);
            }
        } else if (minutes != 0) {
            ret = (minutes + MINUTES);
        }

        return ret;
    }

    public long computeNextRollingTime(long durationSeconds, Date previousRolloverTime) {
        long now              = System.currentTimeMillis();
        long nextRolloverTime = (previousRolloverTime == null) ? now : previousRolloverTime.getTime();
        long durationMillis   = (durationSeconds < 1 ? 1 : durationSeconds) * 1000;

        while (nextRolloverTime <= now) {
            nextRolloverTime += durationMillis;
        }

        return nextRolloverTime;
    }

    private Date computeTopOfYearDate(int years) {
        Calendar calendarStart = Calendar.getInstance();

        calendarStart.add(Calendar.YEAR, years);
        calendarStart.set(Calendar.MONTH, 0);
        calendarStart.set(Calendar.DAY_OF_MONTH, 1);
        calendarStart.set(Calendar.HOUR_OF_DAY, 0);
        calendarStart.clear(Calendar.MINUTE);
        calendarStart.clear(Calendar.SECOND);
        calendarStart.clear(Calendar.MILLISECOND);

        return calendarStart.getTime();
    }

    private Date computeTopofMonthDate(int months) {
        Calendar calendarMonth = Calendar.getInstance();

        calendarMonth.set(Calendar.DAY_OF_MONTH, 1);
        calendarMonth.add(Calendar.MONTH, months);
        calendarMonth.set(Calendar.HOUR_OF_DAY, 0);
        calendarMonth.clear(Calendar.MINUTE);
        calendarMonth.clear(Calendar.SECOND);
        calendarMonth.clear(Calendar.MILLISECOND);

        return calendarMonth.getTime();
    }

    private Date computeTopOfWeekDate(int weeks) {
        Calendar calendarWeek = Calendar.getInstance();

        calendarWeek.set(Calendar.DAY_OF_WEEK, calendarWeek.getFirstDayOfWeek());
        calendarWeek.add(Calendar.WEEK_OF_YEAR, weeks);
        calendarWeek.set(Calendar.HOUR_OF_DAY, 0);
        calendarWeek.clear(Calendar.MINUTE);
        calendarWeek.clear(Calendar.SECOND);
        calendarWeek.clear(Calendar.MILLISECOND);

        return calendarWeek.getTime();
    }

    private Date computeTopOfDayDate(int days) {
        Calendar calendarDay = Calendar.getInstance();

        calendarDay.add(Calendar.DAY_OF_MONTH, days);
        calendarDay.set(Calendar.HOUR_OF_DAY, 0);
        calendarDay.clear(Calendar.MINUTE);
        calendarDay.clear(Calendar.SECOND);
        calendarDay.clear(Calendar.MILLISECOND);

        return calendarDay.getTime();
    }

    private Date computeTopOfHourDate(int hours) {
        Calendar calendarHour = Calendar.getInstance();

        calendarHour.add(Calendar.HOUR_OF_DAY, hours);
        calendarHour.clear(Calendar.MINUTE);
        calendarHour.clear(Calendar.SECOND);
        calendarHour.clear(Calendar.MILLISECOND);

        return calendarHour.getTime();
    }

    private Date computeTopOfMinuteDate(int mins) {
        Calendar calendarMin = Calendar.getInstance();

        calendarMin.add(Calendar.MINUTE, mins);
        calendarMin.clear(Calendar.SECOND);
        calendarMin.clear(Calendar.MILLISECOND);

        return calendarMin.getTime();
    }

    private int getTimeNumeral(String rollOverPeriod, String timeLiteral) {
        return Integer.parseInt(rollOverPeriod.substring(0, rollOverPeriod.length() - (rollOverPeriod.length() - rollOverPeriod.indexOf(timeLiteral))));
    }

    private String getTimeLiteral(String rollOverPeriod) throws Exception {
        final String ret;

        if (StringUtils.isEmpty(rollOverPeriod)) {
            throw new Exception("empty rollover period");
        } else if (rollOverPeriod.endsWith(MINUTES)) {
            ret = MINUTES;
        } else if (rollOverPeriod.endsWith(HOURS)) {
            ret = HOURS;
        } else if (rollOverPeriod.endsWith(DAYS)) {
            ret = DAYS;
        } else if (rollOverPeriod.endsWith(WEEKS)) {
            ret = WEEKS;
        } else if (rollOverPeriod.endsWith(MONTHS)) {
            ret = MONTHS;
        } else if (rollOverPeriod.endsWith(YEARS)) {
            ret = YEARS;
        } else {
            throw new Exception(rollOverPeriod + ": invalid rollover period");
        }

        return ret;
    }
}
