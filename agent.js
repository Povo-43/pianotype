#!/usr/bin/env node
'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const { spawn, execSync } = require('child_process');

// ===================== 設定 =====================
const AUTO_ALLOWED = new Set(['ls', 'cat', 'cd', 'npm', 'node']);
// 引数のパスを必ずルート内に制限するコマンド
const PATH_GUARDED = new Set(['ls', 'cat', 'cd']);
const BLOCK_RE = /\[\[\[\[([\s\S]*?)\]\]\]\](?=[ \t]*(?:\n|$))/g;
const RP_SEP_RE = /[ \t]\/n[ \t]/;
const OP_LABEL = {
  w: 'write', a: 'append', d: 'delete', m: 'mkdir',
  r: 'run', rd: 'read', rp: 'replace',
};

// ===================== パース =====================
function findSepIndices(s) {
  const re = /\n|\\n|[ \t]\/n[ \t]/g;
  const out = [];
  let m;
  while ((m = re.exec(s)) !== null) out.push({ index: m.index, length: m[0].length });
  return out;
}
function splitHeaderBody(payload) {
  const seps = findSepIndices(payload);
  if (seps.length === 0) return [payload, ''];
  const s = seps[0];
  return [payload.slice(0, s.index), payload.slice(s.index + s.length)];
}
function parseBlock(payload) {
  const [headerRaw, body] = splitHeaderBody(payload);
  const header = headerRaw.trim();
  const sepIdx = header.indexOf('::');
  let op, arg;
  if (sepIdx === -1) { op = header; arg = ''; }
  else { op = header.slice(0, sepIdx).trim(); arg = header.slice(sepIdx + 2).trim(); }

  if (op === 'rp') {
    const m = body.match(RP_SEP_RE);
    if (!m) throw new Error('rp には old と new を " /n " で区切ってください');
    return { op, arg, old: body.slice(0, m.index), new: body.slice(m.index + m[0].length) };
  }
  return { op, arg, body };
}
function parseBlocks(text) {
  const ops = [];
  BLOCK_RE.lastIndex = 0;
  let m;
  while ((m = BLOCK_RE.exec(text)) !== null) {
    try { ops.push(parseBlock(m[1])); }
    catch (e) { console.error(`パースエラー: ${e.message}`); }
  }
  return ops;
}

// ===================== パス安全 =====================
function ensureInside(root, abs) {
  const rel = path.relative(root, abs);
  if (rel.startsWith('..') || path.isAbsolute(rel)) {
    throw new Error(`ルート外は禁止: ${abs}`);
  }
  return abs;
}
function resolveInside(root, rel) {
  if (!rel) throw new Error('パスが空です');
  return ensureInside(root, path.resolve(root, rel));
}

// ===================== コマンド解析 =====================
function splitArgs(s) {
  const args = [];
  let cur = '', quote = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quote) {
      if (c === quote) quote = null;
      else if (c === '\\' && i + 1 < s.length) cur += s[++i];
      else cur += c;
    } else if (c === '"' || c === "'") quote = c;
    else if (/\s/.test(c)) { if (cur) { args.push(cur); cur = ''; } }
    else cur += c;
  }
  if (cur) args.push(cur);
  return args;
}
function cmdName(cmd) { return splitArgs(cmd.trim())[0] || ''; }
// glob やシェル機能もここで弾く（自動許可コマンドは shell:false で動かすため）
function hasShellMeta(cmd) { return /[|&;<>`$()*?\[\]{}]/.test(cmd); }
function isAutoAllowed(cmd) {
  return AUTO_ALLOWED.has(cmdName(cmd)) && !hasShellMeta(cmd);
}

// cd/ls/cat のパス引数をワークスペース内に制限。
// cd の場合は新しい絶対 cwd を返す。それ以外は null。違反時は例外。
function guardPathCmd(cmd, root, cwd) {
  const name = cmdName(cmd);
  if (!PATH_GUARDED.has(name)) return null;
  if (hasShellMeta(cmd)) throw new Error(`${name} でシェルメタ文字は禁止`);

  const parts = splitArgs(cmd);
  const pathArgs = parts.slice(1).filter(a => !a.startsWith('-') && a !== '');

  if (name === 'cd') {
    if (pathArgs.length > 1) throw new Error('cd の引数が多すぎます');
    // 引数なしは「プロジェクトルートへ戻る」に倒す（$HOME には行かせない）
    const target = pathArgs[0] || '.';
    if (target.startsWith('~')) throw new Error('~ 参照は禁止');
    const resolved = path.resolve(cwd, target);
    ensureInside(root, resolved);
    return resolved;
  }
  // ls / cat
  for (const a of pathArgs) {
    if (a.startsWith('~')) throw new Error(`~ 参照は禁止: ${a}`);
    ensureInside(root, path.resolve(cwd, a));
  }
  return null;
}

// ===================== プレビュー =====================
function truncate(s, n) {
  s = String(s).replace(/\n/g, '\\n');
  return s.length > n ? s.slice(0, n) + '…' : s;
}
function preview(ops) {
  ops.forEach((o, i) => {
    const label = OP_LABEL[o.op] || `??(${o.op})`;
    console.log(`\n[${i + 1}] ${label}`);
    if (['w', 'a', 'd', 'm', 'rd'].includes(o.op)) {
      console.log(`  path: ${o.arg || '(なし)'}`);
      if (o.op === 'w' || o.op === 'a') {
        const lines = o.body.split('\n');
        for (const l of lines.slice(0, 5)) console.log(`  | ${l}`);
        if (lines.length > 5) console.log(`  | ... (${lines.length} 行)`);
      }
    } else if (o.op === 'rp') {
      console.log(`  path: ${o.arg}`);
      console.log(`  old:  ${truncate(o.old, 60)}`);
      console.log(`  new:  ${truncate(o.new, 60)}`);
    } else if (o.op === 'r') {
      const cmd = (o.arg || o.body).trim();
      console.log(`  cmd:  ${cmd}`);
      console.log(`  auto: ${isAutoAllowed(cmd) ? 'yes' : 'no'}`);
    }
  });
}

// ===================== 実行 =====================
function runCommand(cmd, cwd, useShell) {
  return new Promise((resolve, reject) => {
    let child;
    if (useShell) child = spawn(cmd, { cwd, stdio: 'inherit', shell: true });
    else {
      const [prog, ...rest] = splitArgs(cmd);
      child = spawn(prog, rest, { cwd, stdio: 'inherit' });
    }
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`コマンド失敗 (exit ${code}): ${cmd}`));
    });
  });
}

async function execute(ops, root, initialCwd, opts) {
  let cwd = initialCwd;
  const backups = new Map();
  const created = new Set();

  const backupIfNeeded = async (p) => {
    if (backups.has(p)) return;
    if (fs.existsSync(p)) backups.set(p, await fsp.readFile(p));
    else { backups.set(p, null); created.add(p); }
  };

  try {
    for (const o of ops) {
      if (o.op === 'w' || o.op === 'a') {
        if (!o.arg) throw new Error(`${o.op} にパスがありません`);
        const p = resolveInside(root, o.arg);
        await fsp.mkdir(path.dirname(p), { recursive: true });
        await backupIfNeeded(p);
        if (o.op === 'w') await fsp.writeFile(p, o.body, 'utf8');
        else await fsp.appendFile(p, o.body, 'utf8');

      } else if (o.op === 'rp') {
        const p = resolveInside(root, o.arg);
        if (!fs.existsSync(p)) throw new Error(`ファイルなし: ${o.arg}`);
        const oldText = await fsp.readFile(p, 'utf8');
        if (!oldText.includes(o.old)) throw new Error(`置換対象なし: ${o.arg}`);
        await backupIfNeeded(p);
        const newText = oldText.split(o.old).join(o.new);
        await fsp.writeFile(p, newText, 'utf8');

      } else if (o.op === 'd') {
        if (!o.arg) throw new Error('d にパスがありません');
        const p = resolveInside(root, o.arg);
        if (fs.existsSync(p)) { await backupIfNeeded(p); await fsp.unlink(p); }

      } else if (o.op === 'm') {
        if (!o.arg) throw new Error('m にパスがありません');
        await fsp.mkdir(resolveInside(root, o.arg), { recursive: true });

      } else if (o.op === 'rd') {
        const p = resolveInside(root, o.arg);
        if (!fs.existsSync(p)) throw new Error(`ファイルなし: ${o.arg}`);
        const data = await fsp.readFile(p, 'utf8');
        process.stdout.write(`----- ${o.arg} -----\n`);
        process.stdout.write(data);
        if (!data.endsWith('\n')) process.stdout.write('\n');
        process.stdout.write(`----- end -----\n`);

      } else if (o.op === 'r') {
        const cmd = (o.arg || o.body).trim();
        if (!cmd) continue;
        const name = cmdName(cmd);

        // cd / ls / cat のパス検査（違反時は例外で停止）
        let cdTarget = null;
        if (PATH_GUARDED.has(name)) {
          cdTarget = guardPathCmd(cmd, root, cwd);
        }

        if (name === 'cd') {
          cwd = cdTarget;
          console.log(`  cd -> ${path.relative(root, cwd) || '.'}`);
          continue;
        }

        const auto = isAutoAllowed(cmd);
        if (!auto && !opts.yes) {
          const ok = await askEnterOK(`  ? ${cmd}  [Enter=OK / その他=NG] `);
          if (!ok) { console.log('  スキップ'); continue; }
        }
        console.log(`  $ ${cmd}`);
        await runCommand(cmd, cwd, !auto);

      } else {
        console.log(`  不明な op をスキップ: ${o.op}`);
      }
    }
  } catch (e) {
    console.error(`\nエラー: ${e.message}`);
    console.error('ロールバック中...');
    for (const [p, data] of backups) {
      try {
        if (data === null) { if (fs.existsSync(p)) await fsp.unlink(p); }
        else await fsp.writeFile(p, data);
      } catch (e2) {
        console.error(`  復元失敗: ${p}: ${e2.message}`);
      }
    }
    throw e;
  }
  return cwd;
}

// ===================== 対話 =====================
// 1キー読む（raw mode）。Enter は '\r' か '\n'。Ctrl-C は '\x03'。
function keyPress() {
  return new Promise((resolve) => {
    const stdin = process.stdin;
    const wasRaw = stdin.isRaw || false;
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding('utf8');
    const onData = (chunk) => {
      stdin.removeListener('data', onData);
      stdin.setRawMode(wasRaw);
      resolve(String(chunk));
    };
    stdin.once('data', onData);
  });
}

async function askEnterOK(question) {
  process.stdout.write(question);
  const chunk = await keyPress();
  if (chunk.includes('\x03')) { process.stdout.write('\n'); process.exit(0); }
  const ok = chunk === '\r' || chunk === '\n' || chunk === '\r\n';
  process.stdout.write(ok ? 'OK\n' : 'NG\n');
  return ok;
}

// Enter が来るまで待つ。Ctrl-C なら 'quit' を返す。
async function waitEnter(prompt) {
  process.stdout.write(prompt);
  for (;;) {
    const chunk = await keyPress();
    if (chunk.includes('\x03')) { process.stdout.write('\n'); return 'quit'; }
    if (chunk === '\r' || chunk === '\n' || chunk === '\r\n') {
      process.stdout.write('\n');
      return 'ok';
    }
    // その他のキーは無視して待ち続ける（raw mode なのでエコーされない）
  }
}

function readClipboard() {
  try {
    if (process.platform === 'darwin') return execSync('pbpaste', { encoding: 'utf8' });
    if (process.platform === 'win32') return execSync('powershell -NoProfile -Command Get-Clipboard', { encoding: 'utf8' });
    try { return execSync('xclip -selection clipboard -o', { encoding: 'utf8' }); }
    catch { return execSync('xsel --clipboard --output', { encoding: 'utf8' }); }
  } catch { return null; }
}

function readStdin() {
  return new Promise((resolve) => {
    let buf = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (d) => buf += d);
    process.stdin.on('end', () => resolve(buf));
  });
}

const HELP = `使い方:
  node agent.js            対話モード起動
                            プロンプトで Enter → クリップボード読み取り
                            実行確認も Enter=OK / その他=NG
                            Ctrl-C で終了
  node agent.js --yes      実行確認を全て省略（危険）
  node agent.js --help     このヘルプ
  node agent.js < input    非TTY時は stdin を一括処理

構文:
  [[[[w::path /n 内容]]]]      上書き
  [[[[a::path /n 追記]]]]      追記
  [[[[m::path]]]               ディレクトリ作成
  [[[[rp::path /n 前 /n 後]]]] 置換（全置換）
  [[[[d::path]]]               削除
  [[[[rd::path]]]              読み出し
  [[[[r::コマンド]]]           実行

複数ブロックを並べると上から順に実行。
自動許可: ls, cat, cd, npm, node（cd/ls/cat はルート外禁止）`;

// ===================== main =====================
async function main() {
  const argv = process.argv.slice(2);
  const flags = new Set(argv.filter(a => a.startsWith('--')));
  if (flags.has('--help') || flags.has('-h')) { console.log(HELP); return; }

  const root = path.resolve(process.cwd());
  let cwd = root;
  const yesAll = flags.has('--yes');

  // 非 TTY（パイプ・リダイレクト）は一括処理
  if (!process.stdin.isTTY) {
    const text = await readStdin();
    const ops = parseBlocks(text);
    if (ops.length === 0) { console.error('ブロックなし'); process.exit(1); }
    console.log(`=== プレビュー (${ops.length} 件) ===`);
    preview(ops);
    await execute(ops, root, cwd, { yes: true });
    return;
  }

  // 対話モード
  console.log('=== interactive agent ===');
  console.log(`root: ${root}`);
  console.log('Enter でクリップボード読み取り / Ctrl-C で終了');

  for (;;) {
    const rel = path.relative(root, cwd) || '.';
    const r = await waitEnter(`\n[${rel}] ▶ Enter `);
    if (r === 'quit') break;

    const text = readClipboard();
    if (!text || !text.trim()) {
      console.log('クリップボードが空です');
      continue;
    }

    const ops = parseBlocks(text);
    if (ops.length === 0) {
      console.log('[[[[ ... ]]]] ブロックが見つかりません');
      continue;
    }

    console.log(`\n=== プレビュー (${ops.length} 件) ===`);
    preview(ops);

    if (!yesAll) {
      const ok = await askEnterOK('\n実行? [Enter=OK / その他=中止] ');
      if (!ok) { console.log('中止'); continue; }
    }

    try {
      cwd = await execute(ops, root, cwd, { yes: yesAll });
      console.log('\n完了');
    } catch (e) {
      console.error(`\n実行エラー: ${e.message}`);
    }
  }
  console.log('\n終了');
}

main().catch((e) => { console.error(`致命的: ${e.message}`); process.exit(1); });