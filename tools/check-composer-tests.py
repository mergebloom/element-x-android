#!/usr/bin/env python3
"""Fail closed on missing suites, ignored new cases or any failed JUnit case."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

MODULES = ['libraries/audio/impl', 'libraries/mediaupload/impl', 'libraries/voicerecorder/impl',
           'libraries/textcomposer/impl', 'features/messages/impl']
REQUIRED = {'DefaultAudioFocusTest', 'VideoCompressorCancellationTest', 'VoiceComposerUiTest',
            'CaptionComposerRemountTest', 'RichCaptionRevisionUiTest', 'CaptionHandoffIntegrationTest',
            'MessagesVoiceNavigationTest', 'RetainedVoiceMessageComposerPresenterTest'}
INHERITED_SKIPS = {
    'AndroidMediaPreProcessorTest': {'test processing video no compression', 'test processing jpeg',
                                   'test processing png no compression', 'test processing png and delete',
                                   'test processing video', 'test processing jpeg no compression',
                                   'test processing jpeg and delete', 'test processing gif', 'test processing png'},
    'TimelineViewTest': {'scrolling near to the start of the loaded items triggers a pre-fetch'},
}

def validate(root):
    counts = {'passed': 0, 'skipped': 0}
    seen = set()
    classes = set()
    for module in MODULES:
        reports = list((root / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        assert reports, f'Missing results: {module}'
        module_cases = 0
        for report in reports:
            for case in ET.parse(report).findall('.//testcase'):
                key = (module, case.attrib['classname'], case.attrib['name'])
                assert key not in seen, f'Duplicate case: {key}'
                seen.add(key)
                name = case.attrib['classname'].split('.')[-1]
                classes.add(name)
                module_cases += 1
                assert case.find('failure') is None and case.find('error') is None, f'Failed: {key}'
                if case.find('skipped') is not None:
                    assert case.attrib['name'] in INHERITED_SKIPS.get(name, set()), f'Unexpected skip: {key}'
                    counts['skipped'] += 1
                else:
                    counts['passed'] += 1
        assert module_cases, f'Empty module: {module}'
    assert REQUIRED <= classes, f'Missing required suites: {REQUIRED - classes}'
    return counts

if __name__ == '__main__':
    print(json.dumps(validate(Path('.')), sort_keys=True))
