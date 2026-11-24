"""Condense the draw-state frame traces of an lr.log (SDK cvars --vulkan_gpu_timing --vulkan_gpu_trace_frame
--vulkan_gpu_trace_draw_state: one frame every 10 s, each draw's shaders, viewport transform, scissor, target and the
float constants its vertex and pixel shader use).
  gpu_trace_states.py LOG          the traced frames (index, characters, passes, distinct states, draws)
  gpu_trace_states.py LOG N        frame N: the passes with their draws in order (D<state>, run-length), then every
                                   state once with the start of its constants
  gpu_trace_states.py LOG N full   the same with every state's full text
Pipe the output through grep / cut: a field frame has ~370 states. Used to find how the UI is projected (wide screens,
DECISIONS 2026-11-24).
"""
import re
import sys

log = sys.argv[1]
frames = []
cur = None
with open(log, encoding='utf-8', errors='replace') as f:
    for line in f:
        if 'GPU frame trace (' in line:
            cur = []
            frames.append(cur)
        elif 'GPU frame trace: ' in line and cur is not None:
            cur.append(line.split('GPU frame trace: ', 1)[1].rstrip('\n'))

texts = [''.join(c) for c in frames]
if len(sys.argv) < 3:
    for i, t in enumerate(texts):
        print(i, len(t), 'passes', t.count(' P') + t.startswith('P'), 'states', len(re.findall(r'D\d+\[', t)),
              'draws', len(re.findall(r'D\d+', t)))
    sys.exit()

t = texts[int(sys.argv[2])]
full = len(sys.argv) > 3
states = {}
# tokens: D<n>[...] (state definition, may contain spaces), D<n>, others
pos = 0
out = []
tok = re.compile(r'D(\d+)\[(.*?) n=(\d+)\] |D(\d+) |(\S+) ')
run = []


def flush():
    if run:
        s = []
        last = None
        cnt = 0
        for d in run:
            if d == last:
                cnt += 1
            else:
                if last is not None:
                    s.append('D%s%s' % (last, 'x%d' % cnt if cnt > 1 else ''))
                last, cnt = d, 1
        s.append('D%s%s' % (last, 'x%d' % cnt if cnt > 1 else ''))
        out.append('   ' + ' '.join(s))
        run.clear()


for m in tok.finditer(t + ' '):
    if m.group(1) is not None:
        states[m.group(1)] = m.group(2)
        run.append(m.group(1))
    elif m.group(4) is not None:
        run.append(m.group(4))
    else:
        flush()
        out.append(m.group(5))
print('\n'.join(out))
print()
for k, v in states.items():
    if full:
        print('D%s: %s' % (k, v))
    else:
        head, _, consts = v.partition(' C:')
        print('D%s: %s' % (k, head))
        print('      C:' + consts[:400])
