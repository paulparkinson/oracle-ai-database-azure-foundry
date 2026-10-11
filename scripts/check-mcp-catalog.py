#!/usr/bin/env python3
"""Check the real pinned Oracle MCP server's stdio discovery, without a database call."""
import json
import os
from pathlib import Path
import selectors
import subprocess
import time

root = Path(__file__).resolve().parents[1]
jar = root / '.deps/oracle-ai-database-fullstack-toolkit/upstream/oracle-db-mcp-java-toolkit/target/oracle-db-mcp-toolkit-1.0.0.jar'
env = dict(os.environ, DB_URL='jdbc:oracle:thin:@//127.0.0.1:1/NO_DATABASE',
           MCP_DB_USERNAME='catalog_check_only', MCP_DB_PASSWORD='not-a-credential')
process = subprocess.Popen(['java', '-Dtransport=stdio', '-Dtools=foundry-document-reads',
                            '-DconfigFile=' + str(root / 'foundry-agents-mcp/tools.yaml'), '-jar', str(jar)],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                           bufsize=0, env=env)
selector = selectors.DefaultSelector()
selector.register(process.stdout, selectors.EVENT_READ)
buffer = b''


def send(message):
    process.stdin.write((json.dumps(dict(jsonrpc='2.0', **message)) + '\n').encode())
    process.stdin.flush()


def receive(expected_id):
    global buffer
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if b'\n' not in buffer:
            if not selector.select(timeout=1):
                if process.poll() is not None:
                    raise RuntimeError('MCP server exited before response')
                continue
            part = os.read(process.stdout.fileno(), 65536)
            if not part:
                raise RuntimeError('MCP server stdout closed')
            buffer += part
            continue
        line, buffer = buffer.split(b'\n', 1)
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get('id') == expected_id:
            if 'error' in message:
                raise RuntimeError('MCP protocol error: ' + str(message['error'].get('code')))
            return message['result']
    raise TimeoutError('MCP server response timed out')


try:
    send(dict(id=1, method='initialize', params=dict(protocolVersion='2025-03-26', capabilities={},
              clientInfo=dict(name='azure-demo-catalog-check', version='1.0'))))
    receive(1)
    send(dict(method='notifications/initialized'))
    expected = {'list-rag-documents', 'list-rag-chunks', 'read-rag-chunk'}
    # This pinned server registers its tools just after starting its transport.
    # Wait briefly for registration, but never accept extra tools.
    for request_id in range(2, 22):
        send(dict(id=request_id, method='tools/list', params={}))
        tools = receive(request_id)['tools']
        actual = {tool['name'] for tool in tools}
        if actual == expected or actual - expected:
            break
        time.sleep(0.1)
    assert {tool['name'] for tool in tools} == expected, 'Unexpected tool exposure: ' + ', '.join(tool['name'] for tool in tools)
    for tool in tools:
        schema = tool['inputSchema']
        assert schema['type'] == 'object'
        if tool['name'] == 'read-rag-chunk':
            assert schema['properties']['chunkId']['type'] == 'string'
            assert 'chunkId' in schema['required']
    print('PASS: actual Oracle MCP Toolkit advertises exactly the three configured read tools.')
    print('No database query or Azure request was made.')
finally:
    selector.close()
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
