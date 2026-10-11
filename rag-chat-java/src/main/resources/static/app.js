'use strict';
const $ = id => document.getElementById(id);
const auth = () => ({Authorization: `Bearer ${$('token').value}`});
$('upload').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
  $('ingestion').textContent = 'Embedding and storing document chunks…';
  try {
    const form = new FormData(); form.append('file', $('file').files[0]);
    const response = await fetch('/api/documents', {method:'POST', headers:auth(), body:form});
    if (!response.ok) throw new Error(`Indexing failed (HTTP ${response.status}). Check token, file, database and model configuration.`);
    $('ingestion').textContent = JSON.stringify(await response.json(), null, 2);
  } catch (error) { $('ingestion').textContent = error.message; }
  finally { button.disabled = false; }
});
$('chat').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
  $('answer').textContent = ''; $('sources').replaceChildren();
  try {
    const response = await fetch('/api/chat', {method:'POST', headers:{...auth(),'Content-Type':'application/json'}, body:JSON.stringify({question:$('question').value})});
    if (!response.ok) throw new Error(`Request failed (HTTP ${response.status}).`);
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
    const render = block => {
      const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
      if (!data) return;
      const message = JSON.parse(data);
      if (message.type === 'token') $('answer').append(document.createTextNode(message.data));
      if (message.type === 'error') $('answer').append(document.createTextNode('\n' + message.data.message + ' Request: ' + message.data.requestId));
      if (message.type === 'sources') {
        const info = document.createElement('p');
        info.textContent = `Request ${message.data.requestId} · ${message.data.retrieval} · Chat: ${message.data.chatDeployment} · Embeddings: ${message.data.embeddingDeployment}`;
        $('sources').append(info);
        for (const source of message.data.sources) {
          const details = document.createElement('details'), summary = document.createElement('summary'), pre = document.createElement('pre');
          summary.textContent = `[${source.citation}] ${source.metadata.source} · ${source.chunkId}`;
          pre.textContent = JSON.stringify(source.metadata, null, 2) + '\n\n' + source.excerpt;
          details.append(summary, pre); $('sources').append(details);
        }
      }
    };
    for (;;) {
      const {value,done} = await reader.read(); buffer += decoder.decode(value, {stream:!done}).replace(/\r\n/g,'\n');
      let separator; while ((separator = buffer.indexOf('\n\n')) >= 0) { render(buffer.slice(0, separator)); buffer = buffer.slice(separator + 2); }
      if (done) { if (buffer.trim()) render(buffer); break; }
    }
  } catch (error) { $('answer').append(document.createTextNode(error.message)); }
  finally { button.disabled = false; }
});
