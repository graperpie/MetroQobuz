
import sys

def print_balance(filename):
    with open(filename, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    count = 0
    for i, line in enumerate(lines):
        line_num = i + 1
        for char in line:
            if char == '{':
                count += 1
            elif char == '}':
                count -= 1
        
        if line_num > 4200:
            print(f"Line {line_num}: {count} | {line.strip()}")

if __name__ == "__main__":
    print_balance(sys.argv[1])
