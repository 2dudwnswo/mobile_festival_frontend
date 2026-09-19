// 에뮬레이터 전용 프로젝트 ID. demo- 로 시작하면 Firebase CLI·SDK 가 실제 리소스에 연결하지 않는다.
// 운영 ID(mobokfestivalpub)는 이 도구들에서 절대 쓰지 않는다.
export const PROJECT_ID = 'demo-festival-pub';
if (!PROJECT_ID.startsWith('demo-')) throw new Error('에뮬레이터 프로젝트 ID는 demo- 로 시작해야 합니다');
export function requireEmulators(env = process.env) {
  const parse = (name, port) => {
    const value = env[name];
    if (value !== `127.0.0.1:${port}` && value !== `localhost:${port}`) {
      throw new Error(`${name}가 로컬 에뮬레이터로 명시되지 않았습니다. 실행 거부.`);
    }
    return { host: '127.0.0.1', port };
  };
  const firestore = parse('FIRESTORE_EMULATOR_HOST', 8080);
  const auth = parse('FIREBASE_AUTH_EMULATOR_HOST', 9099);
  for (const key of ['GCLOUD_PROJECT', 'GOOGLE_CLOUD_PROJECT']) {
    if (env[key] && env[key] !== PROJECT_ID) throw new Error('테스트 프로젝트 ID 불일치');
  }
  return { firestore, auth };
}
