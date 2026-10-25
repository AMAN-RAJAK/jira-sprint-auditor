package com.example.jiraaudit;

import java.util.List;

final class AuditDay {
    static final List<Integer> CONFIGURED_DAYS = List.of(3, 10);

    private AuditDay() { }

    static boolean isAllowedRunDay(int day) {
        return day == 3 || day == 10;
    }

    static int policyDay(int runDay) {
        return runDay;
    }
}
