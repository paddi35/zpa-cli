package br.com.felipezorzo.zpa.cli

import com.felipebz.zpa.squid.ZpaIssue

internal object NoSonarFilter {
    fun filter(issues: List<ZpaIssue>, linesWithNoSonar: Set<Int>): List<ZpaIssue> {
        if (linesWithNoSonar.isEmpty()) {
            return issues
        }
        return issues.filterNot { it.primaryLocation.startLine() in linesWithNoSonar }
    }
}
