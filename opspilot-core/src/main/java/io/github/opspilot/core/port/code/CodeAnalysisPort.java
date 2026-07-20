package io.github.opspilot.core.port.code;

import io.github.opspilot.core.port.code.CodeContracts.CodeFinding;
import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;

import java.time.Instant;
import java.util.List;

public interface CodeAnalysisPort {
    List<CodeFinding> analyze(CodeSnapshot snapshot, Instant deadline);
}
