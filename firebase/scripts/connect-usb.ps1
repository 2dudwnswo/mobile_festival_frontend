param([string]$Adb = 'adb')
$ErrorActionPreference = 'Stop'
# -d는 USB 실기기만 선택한다. Android 에뮬레이터에는 연결하지 않는다.
& $Adb -d get-state
if ($LASTEXITCODE -ne 0) { throw 'USB 디버깅이 허용된 실제 폰 한 대를 연결하세요.' }
& $Adb -d reverse tcp:8080 tcp:8080
if ($LASTEXITCODE -ne 0) { throw 'Firestore 포트 연결 실패' }
& $Adb -d reverse tcp:9099 tcp:9099
if ($LASTEXITCODE -ne 0) { throw 'Auth 포트 연결 실패' }
& $Adb -d reverse --list
Write-Output 'USB 로컬 에뮬레이터 포트 연결 완료. 앱 상단 테스트 DB(에뮬레이터) 띠를 확인하세요.'
