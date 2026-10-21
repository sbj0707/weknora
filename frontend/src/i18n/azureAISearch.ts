const en = {
  physicalIndex: 'Azure index name',
  connectionHint: 'Use an Admin API Key or the identity of the application host. Enable RBAC and grant index management, document write and query permissions for managed identity.',
  dimensionHint: 'The vector dimension must match the knowledge base embedding model. This setting is fixed after creation. The index is created on first write.',
  kbDimension: 'This Azure index requires {dimension}-dimensional embeddings. Select a model with the same dimension.',
  testSuccess: 'Authentication and read access succeeded. Index creation and document write permissions are checked on first write.',
  fields: { addr: 'Search service endpoint (HTTPS)', auth_mode: 'Authentication', api_key: 'Admin API Key', managed_identity_client_id: 'User-assigned identity client ID (optional)', index_name: 'Index name prefix', vector_dimensions: 'Vector dimension', text_analyzer: 'Text analyzer' },
  modes: { api_key: 'Admin API Key', managed_identity: 'Managed identity' },
  validation: { dimensions: 'Enter an integer vector dimension between 1 and 4096.', prefix: 'Use a lowercase index prefix of up to 92 characters, starting with a letter and ending with a letter or digit. Consecutive hyphens are not allowed.', connection: 'Complete the endpoint and authentication fields.' },
}
const zh = {
  physicalIndex: 'Azure 实际索引名称',
  connectionHint: '可使用 Admin API Key 或应用宿主的托管身份。托管身份需开启 RBAC，并授予索引管理、文档写入和查询权限。',
  dimensionHint: '向量维度必须与知识库的 Embedding 模型一致，创建后不可修改；首次写入时自动创建索引。',
  kbDimension: '此 Azure 索引要求 {dimension} 维向量，请选择相同维度的 Embedding 模型。',
  testSuccess: '认证与读取连接成功；建索引和文档写入权限将在首次写入时检查。',
  fields: { addr: 'Search Service 地址（HTTPS）', auth_mode: '认证方式', api_key: 'Admin API Key', managed_identity_client_id: '用户分配身份 Client ID（可选）', index_name: '索引名称前缀', vector_dimensions: '向量维度', text_analyzer: '文本分析器' },
  modes: { api_key: 'Admin API Key', managed_identity: '托管身份' },
  validation: { dimensions: '请输入 1–4096 的整数向量维度。', prefix: '索引前缀最多 92 位，使用小写字母、数字和单个连字符，以字母开头、字母或数字结尾。', connection: '请补全服务地址和认证信息。' },
}
const ja: typeof en = {
  physicalIndex: 'Azure インデックス名', connectionHint: 'Admin API Key またはアプリのマネージド ID を使用します。マネージド ID にはインデックス管理、ドキュメント書き込み、クエリの権限が必要です。',
  dimensionHint: 'ベクトル次元は埋め込みモデルと一致する必要があります。作成後は変更できません。初回書き込み時にインデックスを作成します。', kbDimension: 'この Azure インデックスには {dimension} 次元の埋め込みが必要です。', testSuccess: '認証と読み取り接続に成功しました。作成と書き込み権限は初回書き込み時に確認します。',
  fields: { addr: 'サービス URL（HTTPS）', auth_mode: '認証方法', api_key: 'Admin API Key', managed_identity_client_id: 'ユーザー割り当て ID のクライアント ID（任意）', index_name: 'インデックス名の接頭辞', vector_dimensions: 'ベクトル次元', text_analyzer: 'テキスト アナライザー' }, modes: { api_key: 'Admin API Key', managed_identity: 'マネージド ID' },
  validation: { dimensions: '1～4096 の整数次元を入力してください。', prefix: '接頭辞は小文字、数字、単一のハイフンを使用し、92 文字以内にしてください。', connection: 'サービス URL と認証情報を入力してください。' },
}
const ko: typeof en = {
  physicalIndex: 'Azure 인덱스 이름', connectionHint: 'Admin API Key 또는 앱 호스트의 관리 ID를 사용합니다. 관리 ID에는 인덱스 관리, 문서 쓰기 및 쿼리 권한이 필요합니다.',
  dimensionHint: '벡터 차원은 임베딩 모델과 같아야 하며 생성 후 변경할 수 없습니다. 첫 쓰기 시 인덱스를 생성합니다.', kbDimension: '이 Azure 인덱스에는 {dimension}차원 임베딩이 필요합니다.', testSuccess: '인증 및 읽기 연결에 성공했습니다. 생성 및 쓰기 권한은 첫 쓰기 시 확인합니다.',
  fields: { addr: '서비스 URL (HTTPS)', auth_mode: '인증 방법', api_key: 'Admin API Key', managed_identity_client_id: '사용자 할당 ID 클라이언트 ID (선택)', index_name: '인덱스 이름 접두사', vector_dimensions: '벡터 차원', text_analyzer: '텍스트 분석기' }, modes: { api_key: 'Admin API Key', managed_identity: '관리 ID' },
  validation: { dimensions: '1–4096 사이의 정수 차원을 입력하세요.', prefix: '접두사는 소문자, 숫자 및 단일 하이픈으로 최대 92자까지 입력하세요.', connection: '서비스 URL 및 인증 정보를 입력하세요.' },
}
const ru: typeof en = {
  physicalIndex: 'Имя индекса Azure', connectionHint: 'Используйте Admin API Key или управляемое удостоверение приложения с правами управления индексом, записи и поиска документов.',
  dimensionHint: 'Размерность должна совпадать с моделью эмбеддингов. После создания её нельзя изменить. Индекс создаётся при первой записи.', kbDimension: 'Для этого индекса Azure нужны эмбеддинги размерности {dimension}.', testSuccess: 'Аутентификация и чтение доступны. Права создания и записи проверяются при первой записи.',
  fields: { addr: 'Адрес сервиса (HTTPS)', auth_mode: 'Аутентификация', api_key: 'Admin API Key', managed_identity_client_id: 'Client ID назначенного пользователем удостоверения (необязательно)', index_name: 'Префикс имени индекса', vector_dimensions: 'Размерность вектора', text_analyzer: 'Анализатор текста' }, modes: { api_key: 'Admin API Key', managed_identity: 'Управляемое удостоверение' },
  validation: { dimensions: 'Укажите целую размерность от 1 до 4096.', prefix: 'Префикс: до 92 строчных букв, цифр и одиночных дефисов.', connection: 'Заполните адрес и параметры аутентификации.' },
}
export const azureAISearchLocales = { 'zh-CN': zh, 'en-US': en, 'ja-JP': ja, 'ko-KR': ko, 'ru-RU': ru }
