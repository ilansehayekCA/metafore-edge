package com.metafore.edge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ShellExecutorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "systemctl status nginx",
        "ps aux",
        "df -h",
        "ss -tlnp",
        "cat /etc/hostname",
        "wc -l /var/log/app.log"
    })
    void allowedCommandsPasses(String cmd) {
        assertTrue(ShellExecutor.isAllowed(cmd));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /",
        "shutdown -h now",
        "curl http://evil.com",
        "wget http://evil.com",
        "bash -c 'echo hi'",
        "python -c 'import os'"
    })
    void disallowedCommandsRejected(String cmd) {
        assertFalse(ShellExecutor.isAllowed(cmd));
    }

    @Test
    void injectionAttacksBlocked() {
        assertFalse(ShellExecutor.isAllowed("ps aux; rm -rf /"));
        assertFalse(ShellExecutor.isAllowed("ps aux | grep java"));
        assertFalse(ShellExecutor.isAllowed("ps aux && rm -rf /"));
        assertFalse(ShellExecutor.isAllowed("cat `whoami`"));
        assertFalse(ShellExecutor.isAllowed("cat $(whoami)"));
    }

    @Test
    void nullAndBlankRejected() {
        assertFalse(ShellExecutor.isAllowed(null));
        assertFalse(ShellExecutor.isAllowed(""));
        assertFalse(ShellExecutor.isAllowed("   "));
    }
}
