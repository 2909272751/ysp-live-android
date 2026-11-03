import sys, io
sys.stdout.reconfigure(encoding='utf-8')
src = open(r'C:\1\ysp-android\ysp-live-v9.5.py', encoding='utf-8').read().split('\n')
def show(a, b, title=''):
    print(f'==== {title} [{a}-{b}] ====')
    for i in range(a-1, min(b, len(src))):
        print(i+1, src[i])
    print()
if __name__ == '__main__':
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        a = int(args[i]); b = int(args[i+1]); t = args[i+2] if i+2 < len(args) and not args[i+2].isdigit() else ''
        show(a, b, t)
        i += 3 if t else 2
