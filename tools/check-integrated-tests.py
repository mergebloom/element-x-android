#!/usr/bin/env python3
"""Canonical combined JUnit gate: actual cases, never Gradle badge alone."""
import importlib.util
import json
from pathlib import Path
import sys
spec = importlib.util.spec_from_file_location("composer_gate", Path(__file__).with_name("check-composer-tests.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
gate.MODULES += ["libraries/matrix/impl", "features/home/impl", "appnav", "features/location/impl", "features/poll/impl"]
gate.REQUIRED |= {"ThreadsViewTest", "ThreadDirectoryCoordinatorTest", "ThreadTimelineLoaderTest",
                  "ConfirmedThreadSendsTest", "FileRecentThreadsTest", "ExplicitThreadReadReducerTest", "ReasoningSwipeSelectionTest"}
gate.REQUIRED |= {"TargetOwnedComposerModeTest", "MessageComposerContextTest", "DefaultMediaSenderOwnershipTest", "DefaultMessageDraftNavigationGateTest", "RootFlowNodeTest"}
gate.INHERITED_SKIPS.update({
    "StateEventTypeTest": {"mapping Rust type should work"},
    "MessageEventTypeKtTest": {"map Rust type should result to correct Kotlin type"},
})
if __name__ == "__main__":
    print(json.dumps(gate.validate(Path(sys.argv[1] if len(sys.argv) > 1 else ".")), sort_keys=True))
