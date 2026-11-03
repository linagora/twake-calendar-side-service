"""Summarize Surefire failures for the Jenkins PR comment using only the standard library."""

from html import escape
from pathlib import Path
import xml.etree.ElementTree as ET


def excerpt(text, limit):
    return escape(" ".join(text.split())[:limit], quote=False).replace("`", "'")


reports = sorted(Path('.').glob('**/target/surefire-reports/TEST-*.xml'))
failures = []
failure_count = 0
unreadable = 0
for report in reports:
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError):
        unreadable += 1
        continue
    for test in root.iter('testcase'):
        failure = next((child for child in test if child.tag in {'failure', 'error'}), None)
        if failure is None:
            continue
        failure_count += 1
        if len(failures) == 10:
            continue
        name = excerpt(f"{test.get('classname', '')}#{test.get('name', '')}", 300)
        message = failure.get('message') or (failure.text or '').strip().split('\n')[0]
        failures.append((name, excerpt(message, 300)))

if failures:
    print(f'**Recorded test failures ({failure_count}):**\n')
    # Bound the comment size; full failure details remain in Jenkins.
    for name, message in failures:
        print(f'> `{name}`')
        if message:
            print(f'> `{message}`')
        print()
    if failure_count > 10:
        print(f'Showing the first 10; {failure_count - 10} more in the build report.\n')
elif reports and not unreadable:
    print('> No failed test cases were found in the available Surefire reports. See the build log for other failures.')
elif not reports:
    print('> No Surefire XML reports were available. See the build log.')

if unreadable:
    print(f'> Could not read {unreadable} Surefire XML report(s). See the build log.')
