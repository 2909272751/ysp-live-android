import sys, os, json, time, importlib.util
sys.path.insert(0, r'C:\1\ysp-android')
spec = importlib.util.spec_from_file_location('ysp', r'C:\1\ysp-android\ysp-live-v9.5.py')
# Can't import as module because script runs main() at bottom guarded? check
src = open(r'C:\1\ysp-android\ysp-live-v9.5.py', encoding='utf-8').read()
print('ends with:', src[-200:].replace('\n', '|'))
