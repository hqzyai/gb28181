#!/usr/bin/env python3
"""Observe the shared GPU and stop only the explicitly named PPE container on budget violations."""
import argparse
import csv
import datetime
import json
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--reports', type=Path, required=True)
parser.add_argument('--preflight-seconds', type=int, default=0)
parser.add_argument('--container')
parser.add_argument('--gpu-budget-mib', type=int, default=8192)
parser.add_argument('--reserve-mib', type=int, default=2048)
args = parser.parse_args()
assert args.preflight_seconds > 0 or args.container
if args.container:
    assert args.container.startswith('gb28181-ppe-'), 'Only this task container may be stopped'
args.reports.mkdir(parents=True, exist_ok=True)

def gpu():
    raw = subprocess.check_output(['nvidia-smi', '--query-gpu=memory.total,memory.used,memory.free,utilization.gpu',
        '--format=csv,noheader,nounits'], text=True, timeout=8).strip().splitlines()[0]
    return [int(value.strip()) for value in raw.split(',')]

def applications():
    raw = subprocess.check_output(['nvidia-smi', '--query-compute-apps=pid,used_memory',
        '--format=csv,noheader,nounits'], text=True, timeout=8)
    result = {}
    for line in raw.splitlines():
        pid, memory = [value.strip() for value in line.split(',')]
        if pid.isdigit() and memory.isdigit():
            result[int(pid)] = int(memory)
    return result

baseline = applications()
min_free = None
max_task_memory = 0
low_count = high_count = 0
started = time.monotonic()
reason = None
status = 'passed'
log_name = 'gpu-preflight.csv' if args.preflight_seconds else args.container + '-gpu.csv'
with (args.reports / log_name).open('w', newline='') as file:
    writer = csv.writer(file)
    writer.writerow(['timestamp_utc', 'total_mib', 'used_mib', 'free_mib', 'utilization_percent', 'task_mib', 'other_processes'])
    while True:
        total, used, free, utilization = gpu()
        min_free = free if min_free is None else min(min_free, free)
        task_memory = 0
        apps = applications()
        if args.container:
            inspect = subprocess.run(['docker', 'inspect', args.container], capture_output=True, text=True, timeout=8)
            if inspect.returncode:
                reason = 'Task container not found'
                status = 'failed'
                break
            state = json.loads(inspect.stdout)[0]['State']
            task_memory = apps.get(state['Pid'], 0)
            max_task_memory = max(max_task_memory, task_memory)
            if not state['Running']:
                reason = 'Task finished; container exit=' + str(state['ExitCode'])
                status = 'passed' if state['ExitCode'] == 0 else 'failed'
                break
            low_count = low_count + 1 if free < args.reserve_mib else 0
            high_count = high_count + 1 if task_memory > args.gpu_budget_mib else 0
            if low_count >= 10 or high_count >= 5:
                reason = 'GPU reserve below limit' if low_count >= 10 else 'Task GPU memory budget exceeded'
                status = 'stopped'
                subprocess.run(['docker', 'stop', '--time', '15', args.container], check=True, timeout=25)
                break
        writer.writerow([datetime.datetime.now(datetime.timezone.utc).isoformat(), total, used, free, utilization,
            task_memory, json.dumps({pid: memory for pid, memory in apps.items() if pid in baseline})])
        file.flush()
        if args.preflight_seconds and time.monotonic() - started >= args.preflight_seconds:
            if min_free < args.gpu_budget_mib + args.reserve_mib:
                status, reason = 'failed', 'Free memory is insufficient for requested budget plus reserve'
            break
        time.sleep(1)

report = {'status': status, 'reason': reason, 'sample_duration_seconds': round(time.monotonic() - started, 1),
    'min_actual_free_mib': min_free, 'max_task_memory_mib': max_task_memory,
    'gpu_budget_mib': args.gpu_budget_mib, 'reserve_mib': args.reserve_mib,
    'baseline_gpu_processes': baseline, 'container': args.container, 'csv': log_name}
(args.reports / log_name.replace('.csv', '.json')).write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
if status != 'passed':
    raise SystemExit(1)
