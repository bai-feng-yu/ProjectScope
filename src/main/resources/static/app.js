const $ = id => document.getElementById(id);
let token = '', session = 0, conversationId = null, runId = null, stream = null;
let pendingSend = null, pendingSchedule = null;
const terminal = status => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status);
const note = message => { $('notice').textContent = message; };
const element = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text; // Model/user output is never interpreted as HTML.
  if (className) node.className = className;
  return node;
};
function button(text, action) {
  const node = element('button', text, 'secondary');
  node.type = 'button';
  node.onclick = () => guarded(action);
  return node;
}
async function guarded(action) {
  try { await action(); } catch (error) { if (error.message !== 'SESSION_CHANGED') note(error.message); }
}
async function api(path, method = 'GET', body, key) {
  if (!token) throw new Error('请先连接身份令牌。');
  const current = session;
  const response = await fetch(path, { method, headers: {
    Authorization: `Bearer ${token}`, ...(body === undefined ? {} : {'Content-Type': 'application/json'}),
    ...(key ? {'Idempotency-Key': key} : {})
  }, ...(body === undefined ? {} : {body: JSON.stringify(body)}) });
  if (current !== session) throw new Error('SESSION_CHANGED');
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) disconnect();
    const hints = {401:'令牌无效或已过期，请重新连接。',403:'账户或操作未获授权。',404:'资源不存在或不属于当前用户。',409:'状态已变化或请求键冲突，请刷新后重试。',503:'存储暂不可用，请稍后重试。'};
    throw new Error(hints[response.status] || `请求失败（${response.status}）`);
  }
  const text = await response.text();
  if (current !== session) throw new Error('SESSION_CHANGED');
  return text ? JSON.parse(text) : null;
}
function disconnect() {
  session++; token = ''; conversationId = null; runId = null; pendingSend = null; pendingSchedule = null;
  stream?.abort(); stream = null; $('token').value = '';
  for (const id of ['conversations','messages','memories','schedules','run-events','run-answer']) $(id).replaceChildren();
  $('conversation-title').textContent = '选择或新建对话'; $('run-status').textContent = '尚未选择 Run';
  $('cancel-run').disabled = true; $('memory-enabled').checked = false;
  $('message').value = ''; $('schedule-content').value = ''; note('已断开；本页身份及数据已清除。');
}
async function refreshConversations() {
  const rows = await api('/conversations');
  $('conversations').replaceChildren(...rows.map(row => {
    const node = button(row.title, async () => {
      conversationId = row.conversationId; pendingSend = null; pendingSchedule = null;
      $('conversation-title').textContent = row.title; await refreshConversations(); await refreshMessages();
    });
    node.classList.toggle('active', row.conversationId === conversationId); return node;
  }));
}
async function refreshMessages() {
  if (!conversationId) return;
  const selected = conversationId;
  const rows = await api(`/conversations/${selected}/messages`);
  if (selected !== conversationId) return;
  $('messages').replaceChildren(...rows.map(row => {
    const node = element('article', undefined, `message ${row.role.toLowerCase()}`);
    node.append(element('small', `${row.role === 'USER' ? '你' : 'ProjectScope'} · ${row.status}`), element('div', row.content));
    if (row.errorMessage) node.append(element('small', row.errorMessage));
    if (row.runId) node.append(button('查看运行', () => watchRun(row.runId)));
    return node;
  }));
  $('messages').scrollTop = $('messages').scrollHeight;
}
async function refreshMemories() {
  const [rows, settings] = await Promise.all([api('/memories'), api('/memories/settings')]);
  $('memory-enabled').checked = settings.enabled;
  $('memories').replaceChildren(...rows.map(row => {
    const card = element('article', undefined, 'card');
    const editor = element('textarea'); editor.value = row.content; editor.maxLength = 2000; editor.rows = 3;
    editor.setAttribute('aria-label', `记忆内容：${row.topic}`);
    card.append(element('span', `${row.status === 'CANDIDATE' ? '待确认' : '已确认'} · ${row.kind} · v${row.version}`, 'badge'),
      element('strong', row.topic), editor, element('p', `来源消息：${row.sourceMessageId}`, 'hint'));
    const actions = element('div', undefined, 'actions');
    if (row.status === 'CANDIDATE') {
      actions.append(button('确认', async () => { await api(`/memories/${row.memoryId}/confirm`, 'POST', {version:row.version,replaceExisting:false}); await refreshMemories(); }),
        button('确认并替换同主题', async () => {
          if (!confirm('确认以此候选替换已确认的同主题记忆？')) return;
          await api(`/memories/${row.memoryId}/confirm`, 'POST', {version:row.version,replaceExisting:true}); await refreshMemories();
        }));
      editor.readOnly = true;
    } else actions.append(button('保存纠正', async () => {
      if (!editor.value.trim()) throw new Error('记忆内容不能为空。');
      await api(`/memories/${row.memoryId}`, 'PUT', {version:row.version,content:editor.value}); await refreshMemories();
    }));
    actions.append(button('删除主题记忆', async () => {
      if (!confirm('删除此主题的所有记忆版本？原始聊天仍会保留。')) return;
      await api(`/memories/${row.memoryId}?version=${row.version}`, 'DELETE'); await refreshMemories();
    }));
    card.append(actions); return card;
  }));
}
async function refreshSchedules() {
  const rows = await api('/schedules');
  $('schedules').replaceChildren(...rows.map(row => {
    const card = element('article', undefined, 'card');
    const when = new Date(`${row.nextFireAt}Z`).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai'});
    card.append(element('span', row.paused ? '已暂停' : `下次：${when} 北京时间`, 'badge'), element('p', row.content));
    if (row.lastOutcome) card.append(element('p', `上次触发：${row.lastOutcome}`, 'hint'));
    card.append(button(row.paused ? '恢复' : '暂停', async () => {
      await api(`/schedules/${row.scheduleId}/pause`, 'PUT', {paused:!row.paused}); await refreshSchedules();
    }));
    if (row.lastRunId) card.append(button('查看最近运行', () => watchRun(row.lastRunId)));
    return card;
  }));
}
async function refreshAll() { await Promise.all([refreshConversations(), refreshMessages(), refreshMemories(), refreshSchedules()]); }
async function watchRun(id) {
  stream?.abort(); const controller = new AbortController(); stream = controller; runId = id;
  $('run-events').textContent = ''; $('run-answer').textContent = ''; let cursor = null;
  const current = session;
  async function snapshot() {
    const run = await api(`/runs/${id}`);
    if (controller.signal.aborted || current !== session) return true;
    $('run-status').textContent = `${id} · ${run.status}${run.failureReason ? ' · ' + run.failureReason : ''}`;
    $('run-answer').textContent = run.finalAnswer || '';
    $('cancel-run').disabled = terminal(run.status());
    return terminal(run.status());
  }
  await snapshot();
  // Fetch supports Authorization headers; native EventSource does not. Reconnect using durable event IDs.
  void guarded(async () => {
    while (!controller.signal.aborted && current === session) {
      try {
        const response = await fetch(`/runs/${id}/events`, {signal:controller.signal,
          headers:{Authorization:`Bearer ${token}`, Accept:'text/event-stream', ...(cursor ? {'Last-Event-ID':cursor} : {})}});
        if (controller.signal.aborted || current !== session) return;
        if (response.status === 204) return;
        if (!response.ok) throw new Error(`事件流不可用（${response.status}），请刷新或重新选择运行。`);
        const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
        while (true) {
          const {done,value} = await reader.read(); if (done) break;
          if (controller.signal.aborted || current !== session) return;
          buffer += decoder.decode(value, {stream:true}).replace(/\r/g, '');
          let end;
          while ((end = buffer.indexOf('\n\n')) >= 0) {
            const event = buffer.slice(0,end); buffer = buffer.slice(end+2);
            const eventId = event.match(/^id:\s*(.*)$/m); if (eventId) cursor = eventId[1];
            if (event.startsWith(':')) continue;
            $('run-events').textContent = ($('run-events').textContent + '\n' + event).slice(-16000);
            $('run-events').scrollTop = $('run-events').scrollHeight;
          }
        }
        const finished = await snapshot(); await refreshMessages();
        if (finished) return;
        await new Promise(resolve => setTimeout(resolve, 1500));
      } catch (error) { if (controller.signal.aborted || current !== session) return; throw error; }
    }
  });
}
$('connect-form').onsubmit = event => { event.preventDefault(); const value = $('token').value.trim(); disconnect(); token = value;
  void guarded(async () => { await refreshAll(); note('已连接。记忆、对话和研究任务均按当前用户隔离。'); }); };
$('disconnect').onclick = disconnect;
$('refresh').onclick = () => guarded(refreshAll);
$('new-conversation').onsubmit = event => { event.preventDefault(); void guarded(async () => {
  const created = await api('/conversations','POST',{title:$('title').value}); conversationId = created.conversationId;
  $('conversation-title').textContent = $('title').value; $('title').value = ''; await refreshAll();
}); };
$('send-form').onsubmit = event => { event.preventDefault(); const mode = event.submitter?.value || 'chat'; void guarded(async () => {
  if (!conversationId) throw new Error('请先新建或选择对话。');
  const selected = conversationId, content = $('message').value;
  const fingerprint = JSON.stringify([selected,mode,content]);
  if (pendingSend?.fingerprint !== fingerprint) pendingSend = {fingerprint,key:crypto.randomUUID()};
  const buttons = [...$('send-form').querySelectorAll('button')]; buttons.forEach(node => node.disabled=true);
  try {
    note(mode === 'research' ? '正在保存研究任务…' : '正在生成回复和提取记忆候选…');
    const result = await api(`/conversations/${selected}/${mode === 'research' ? 'research' : 'messages'}`, 'POST', {content}, pendingSend.key);
    pendingSend = null; if (selected === conversationId) $('message').value = '';
    await refreshAll(); note('已保存。'); if (result.runId) await watchRun(result.runId);
  } finally { buttons.forEach(node => node.disabled=false); }
}); };
$('memory-enabled').onchange = () => guarded(async () => {
  await api('/memories/settings','PUT',{enabled:$('memory-enabled').checked}); await refreshMemories();
});
$('cancel-run').onclick = () => guarded(async () => { if (!runId) return; await api(`/runs/${runId}/cancel`,'POST'); await watchRun(runId); });
$('schedule-form').onsubmit = event => { event.preventDefault(); void guarded(async () => {
  if (!conversationId) throw new Error('请先选择一个对话，用来接收每周研究结果。');
  const body = {conversationId, content:$('schedule-content').value, firstFireAt:new Date($('first-fire').value + '+08:00').toISOString()};
  const fingerprint = JSON.stringify(body);
  if (pendingSchedule?.fingerprint !== fingerprint) pendingSchedule={fingerprint,key:crypto.randomUUID()};
  await api('/schedules','POST',body,pendingSchedule.key); pendingSchedule=null;
  await refreshSchedules(); note('已授权每周执行，可随时暂停。');
}); };
