import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  validateFileChange,
  clampTerminalTimeoutSeconds,
  MAX_TERMINAL_TIMEOUT_SECONDS,
  validateClientToolCall,
  CLIENT_TOOL_NAMES,
} from './client-tool-schema.mjs';

test('16 client/host tools match pinned clientToolCallSchema', () => {
  assert.equal(CLIENT_TOOL_NAMES.length, 16);
  assert.ok(CLIENT_TOOL_NAMES.includes('run_terminal_command'));
  assert.ok(CLIENT_TOOL_NAMES.includes('write_file'));
});

test('unknown / newly-introduced upstream tool fails loudly (§26)', () => {
  const r = validateClientToolCall({ toolName: 'brand_new_tool', input: {} });
  assert.equal(r.ok, false);
  assert.match(r.errors[0], /unsupported upstream tool/);
});

test('FileChangeSchema accepts patch and file', () => {
  assert.deepEqual(validateFileChange({ type: 'patch', path: 'a/b.ts', content: 'x' }), []);
  assert.deepEqual(validateFileChange({ type: 'file', path: 'a.ts', content: '' }), []);
});

test('FileChangeSchema rejects bad type / missing fields', () => {
  assert.ok(validateFileChange({ type: 'nope', path: 'a', content: '' }).length >= 1);
  assert.ok(validateFileChange({ type: 'file', path: '', content: '' }).length >= 1);
  assert.ok(validateFileChange({ type: 'file', path: 'a' }).length >= 1);
});

test('write_file dispatched without valid FileChange is rejected', () => {
  const r = validateClientToolCall({ toolName: 'write_file', input: { type: 'file', path: '' } });
  assert.equal(r.ok, false);
});

test('run_terminal_command requires non-empty command + mode', () => {
  assert.equal(validateClientToolCall({
    toolName: 'run_terminal_command', input: { command: 'ls', mode: 'assistant' },
  }).ok, true);
  const noMode = validateClientToolCall({ toolName: 'run_terminal_command', input: { command: 'ls' } });
  assert.equal(noMode.ok, false);
  const empty = validateClientToolCall({ toolName: 'run_terminal_command', input: { command: '', mode: 'user' } });
  assert.equal(empty.ok, false);
});

test('timeout clamp mirrors upstream semantics', () => {
  assert.equal(clampTerminalTimeoutSeconds(undefined), undefined);
  assert.equal(clampTerminalTimeoutSeconds(-1), -1);
  assert.equal(clampTerminalTimeoutSeconds(0), 30);
  assert.equal(clampTerminalTimeoutSeconds(-5), 30);
  assert.equal(clampTerminalTimeoutSeconds(50), 50);
  assert.equal(clampTerminalTimeoutSeconds(99999), MAX_TERMINAL_TIMEOUT_SECONDS);
  assert.equal(clampTerminalTimeoutSeconds(Number.NaN), MAX_TERMINAL_TIMEOUT_SECONDS);
});
