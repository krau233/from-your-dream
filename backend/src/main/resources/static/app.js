const el = id => document.getElementById(id);
let userId = localStorage.getItem('dream.userId') || '';
let game = null;
let busy = false;
let pending = null;
el('account-id').value = userId;

async function api(path, { method = 'GET', body, account = userId } = {}) {
  const headers = {};
  if (account) headers['X-User-Id'] = account;
  if (body) headers['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  } catch {
    throw new Error('连接中断。重试相同输入时会复用本轮请求，也可以刷新查看已保存的进度。');
  }
  const result = await response.json();
  if (!response.ok || result.code !== 200) {
    const error = new Error(result.message || '操作失败，请稍后重试。');
    error.status = response.status;
    throw error;
  }
  return result.data;
}

function status(message, error = false) {
  el('status').textContent = message;
  el('status').classList.toggle('error', error);
}

async function run(message, action) {
  if (busy) return;
  busy = true;
  render();
  status(message);
  try {
    await action();
  } catch (error) {
    status(error.message, true);
  } finally {
    busy = false;
    render();
  }
}

function text(tag, content, className = '') {
  const node = document.createElement(tag);
  node.textContent = content;
  node.className = className;
  return node;
}

function responseCard(response) {
  const article = document.createElement('article');
  article.className = 'dialogue';
  article.append(text('p', `${response.characterName} · ${emotion(response.emotion)}`, 'speaker'));
  if (response.narration) article.append(text('p', response.narration, 'response-narration'));
  if (response.dialogue) article.append(text('p', response.dialogue, 'dialogue-text'));
  return article;
}

function render() {
  document.querySelectorAll('button, input, select, textarea').forEach(node => { node.disabled = busy; });
  el('game').hidden = !game;
  if (!game) return;
  el('progress').textContent = `身份 ${game.userId} · 已互动 ${game.turn} / 最多 ${game.maxTurns} 段`;
  const last = game.history.at(-1);
  const opening = !last ? game.openingPrompt : null;
  const present = game.characters.filter(character => game.scene.presentCharacterIds.includes(character.id));
  el('input-label').textContent = '你想说些什么，或做些什么？';
  el('presence-label').textContent = present.length ? '此刻在场' : '此刻这里只有你';
  el('characters').replaceChildren(...present.map(character => {
    const card = document.createElement('article');
    card.className = 'character';
    card.append(text('strong', character.name), text('span', character.intro));
    return card;
  }));
  el('scene-label').textContent = game.scene.title;
  el('end-conversation').hidden = !last;
  el('narration').hidden = Boolean(last);
  el('narration').textContent = game.opening;
  el('scene-environment').hidden = !last;
  el('scene-environment').textContent = game.scene.environment;
  el('player-input').hidden = !last;
  el('player-input').textContent = last ? `你：${last.playerInput}` : '';
  const responses = last ? last.responses : opening ? [{
    characterName: game.characters.find(character => character.id === opening.characterId).name,
    narration: '', dialogue: opening.dialogue, emotion: 'calm'
  }] : [];
  el('responses').replaceChildren(...responses.map(responseCard));
  if (!responses.length) el('responses').append(text('p', '一时没有人接话。', 'narration'));
  el('opening-responses').hidden = !opening?.suggestedReplies?.length;
  el('opening-responses').replaceChildren(...(opening?.suggestedReplies || []).map(reply => {
    const button = text('button', reply);
    button.type = 'button';
    button.disabled = busy;
    button.addEventListener('click', () => submitTurn(reply));
    return button;
  }));
  el('story-beat').hidden = !last?.storyNarration;
  el('story-narration').textContent = last?.storyNarration || '';
  el('ending-panel').hidden = !game.ended;
  el('ending-text').textContent = game.ending;
  el('chat-form').hidden = game.ended;
  el('send-button').textContent = busy ? '正在演绎…' : !last ? '回应' : '发送';
}

function emotion(value) {
  return { calm: '平静', happy: '欣喜', sad: '低落', surprised: '惊讶', angry: '不悦' }[value] || '平静';
}

async function refreshAccounts() {
  const keys = await api('/api/redis/user-keys', { account: '' });
  el('accounts').replaceChildren(new Option('选择已有身份', ''), ...keys
    .filter(key => /^user_[A-Za-z0-9]{8}$/.test(key))
    .sort().map(key => new Option(key.slice(5), key.slice(5))));
}

async function enter(account) {
  const next = await api('/api/game', { account: account.trim() });
  game = next;
  userId = next.userId;
  pending = null;
  el('account-id').value = userId;
  el('message').value = '';
  el('history-panel').hidden = true;
  localStorage.setItem('dream.userId', userId);
  status(`已进入身份 ${userId} 的故事。`);
}

el('use-account').addEventListener('click', () => run('正在读取故事…', () => enter(el('account-id').value)));
el('accounts').addEventListener('change', event => {
  if (event.target.value) run('正在切换身份…', () => enter(event.target.value));
});
el('refresh-accounts').addEventListener('click', () => run('正在读取身份…', async () => {
  await refreshAccounts();
  status('身份列表已更新。');
}));
el('create-account').addEventListener('click', () => run('正在生成身份…', async () => {
  const account = await api('/api/redis/user-code', { method: 'POST', account: '' });
  // 先展示新 ID，即使后续网络中断也能手动恢复。
  el('account-id').value = account;
  await enter(account);
  await refreshAccounts();
}));

function submitTurn(message, endConversation = false) {
  if (!game || (!message && !endConversation) || busy || game.ended) return;
  if (!pending || pending.gameId !== game.gameId
      || pending.message !== message || pending.endConversation !== endConversation) {
    pending = { gameId: game.gameId, requestId: crypto.randomUUID(), expectedTurn: game.turn,
      message, endConversation };
  }
  run('正在演绎对话与剧情，请稍候…', async () => {
    try {
      game = await api('/api/game/turns', { method: 'POST', body: pending });
      pending = null;
      el('message').value = '';
      el('history-panel').hidden = true;
      status(game.ended ? '这一夜已经落幕，你可以回看故事或重新开始。' : '这段经历已保存。');
    } catch (error) {
      if (error.status === 409) {
        game = await api('/api/game');
        pending = null;
      }
      throw error;
    }
  });
}

el('chat-form').addEventListener('submit', event => {
  event.preventDefault();
  submitTurn(el('message').value.trim());
});
el('end-conversation').addEventListener('click', () => submitTurn('', true));

el('reset-button').addEventListener('click', () => run('正在重新开始…', async () => {
  game = await api('/api/game/reset', { method: 'POST', body: { gameId: game.gameId } });
  pending = null;
  el('message').value = '';
  el('history-panel').hidden = true;
  status('新的一局开始了。');
}));

el('history-button').addEventListener('click', () => run('正在读取历史…', async () => {
  const turns = await api('/api/game/history');
  const opening = document.createElement('article');
  opening.className = 'history-turn';
  opening.append(text('p', '开场', 'history-label'), text('p', game.opening, 'history-narration'));
  if (game.openingPrompt) {
    const speaker = game.characters.find(character => character.id === game.openingPrompt.characterId);
    opening.append(text('p', `${speaker.name}：${game.openingPrompt.dialogue}`));
  }
  el('history-list').replaceChildren(opening, ...turns.map(turn => {
    const article = document.createElement('article');
    article.className = 'history-turn';
    const witnesses = turn.witnessIds.map(id => game.characters.find(character => character.id === id).name);
    article.append(text('p', `第 ${turn.number} 段 · ${witnesses.length ? '在场：' + witnesses.join('、') : '独处'}`, 'history-label'),
      text('p', `你：${turn.playerInput}`));
    turn.responses.forEach(response => {
      if (response.narration) article.append(text('p', response.narration, 'history-narration'));
      if (response.dialogue) article.append(text('p', `${response.characterName}：${response.dialogue}`));
    });
    if (!turn.responses.length) article.append(text('p', '一时没有人接话。', 'history-narration'));
    if (turn.storyNarration) article.append(text('p', turn.storyNarration, 'history-narration'));
    return article;
  }));
  el('history-panel').hidden = false;
  el('history-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  status('历史对话已读取。');
}));
el('close-history').addEventListener('click', () => { el('history-panel').hidden = true; });

run('正在读取身份…', async () => {
  await refreshAccounts();
  if (userId) await enter(userId);
  else status('先生成一个身份，或选择已有身份进入故事。');
});
