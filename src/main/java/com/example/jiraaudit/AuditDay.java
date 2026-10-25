package com.example.jiraaudit;

import java.util.List;

final class AuditDay {
    static final List<Integer> CONFIGURED_DAYS = List.of(1, 3, 7, 10);

    private AuditDay() { }

    static boolean isAllowedRunDay(int day) {
        return day == 1 || day == 3 || day == 7 || day >= 10;
    }

    static int policyDay(int runDay) {
        return runDay >= 10 ? 10 : runDay;
    }
}
