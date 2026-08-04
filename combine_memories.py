import os

source_dir = r'C:\Users\yaswa\.claude\memory'
output_file = 'combined_memories.md'

with open(output_file, 'w', encoding='utf-8') as outfile:
    outfile.write('# ?? Imported Permanent Memories\n\n')
    
    for root, dirs, files in os.walk(source_dir):
        # Exclude raw_chats or other heavy data folders if they exist
        if 'raw_chats' in root:
            continue
            
        for file in files:
            if file.endswith('.md'):
                file_path = os.path.join(root, file)
                rel_path = os.path.relpath(file_path, source_dir)
                
                outfile.write(f'\n## From {rel_path}\n\n')
                try:
                    with open(file_path, 'r', encoding='utf-8') as infile:
                        outfile.write(infile.read())
                        outfile.write('\n\n')
                except Exception as e:
                    outfile.write(f'Error reading {file_path}: {e}\n')

print(f'Combined memories written to {output_file}')
