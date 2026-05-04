
import sys

def print_balance(filename, start, end):
    with open(filename, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    count = 0
    for i, line in enumerate(lines):
        line_num = i + 1
        start_count = count
        for char in line:
            if char == '{':
                count += 1
            elif char == '}':
                count -= 1
        
        if start <= line_num <= end:
            print(f"Line {line_num}: {start_count} -> {count} | {line.strip()}")

if __name__ == "__main__":
    print_balance(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]))
