
import sys

def count_braces(filename):
    with open(filename, 'r', encoding='utf-8') as f:
        content = f.read()
    
    count = 0
    for i, char in enumerate(content):
        if char == '{':
            count += 1
        elif char == '}':
            count -= 1
        
        if count < 0:
            print(f"Brace mismatch at character {i}")
            return
    
    print(f"Final count: {count}")

if __name__ == "__main__":
    count_braces(sys.argv[1])
