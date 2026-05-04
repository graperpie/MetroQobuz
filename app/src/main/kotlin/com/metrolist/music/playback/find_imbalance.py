
import sys

def find_imbalance(filename):
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
            
            if count < 0:
                print(f"Negative balance at line {line_num}: {line.strip()}")
                return
    
    print(f"Final balance: {count}")

if __name__ == "__main__":
    find_imbalance(sys.argv[1])
