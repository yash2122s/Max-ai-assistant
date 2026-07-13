import os
import sys
import ast
import unittest

class TestArchitecture(unittest.TestCase):
    def setUp(self):
        self.root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    def test_imports_boundaries(self):
        """
        Scan all python files and inspect import statements using AST
        to enforce architectural layer boundaries.
        """
        for root, _, files in os.walk(self.root_dir):
            for file in files:
                if not file.endswith(".py"):
                    continue
                    
                file_path = os.path.join(root, file)
                rel_path = os.path.relpath(file_path, self.root_dir)
                
                # Exclude tests and external files
                if rel_path.startswith("tests") or rel_path == "exit_test_runner.py":
                    continue
                    
                with open(file_path, "r", encoding="utf-8") as f:
                    try:
                        node = ast.parse(f.read(), filename=file_path)
                    except SyntaxError:
                        continue
                        
                for subnode in ast.walk(node):
                    # Check import and import-from statements
                    imported_modules = []
                    if isinstance(subnode, ast.Import):
                        for name in subnode.names:
                            imported_modules.append(name.name)
                    elif isinstance(subnode, ast.ImportFrom):
                        if subnode.module:
                            imported_modules.append(subnode.module)
                            
                    for mod in imported_modules:
                        # Core layer must not import Server layer
                        if rel_path.startswith("core") and "server" in mod:
                            self.fail(f"Architecture violation in {rel_path}: 'core' module imports 'server' module '{mod}'")
                            
                        # Tools layer must not import Server layer
                        if rel_path.startswith("tools") and "server" in mod:
                            self.fail(f"Architecture violation in {rel_path}: 'tools' module imports 'server' module '{mod}'")
                            
                        # Protocol layer must not import any application layer
                        if rel_path.startswith("protocol") and any(app in mod for app in ["core", "server", "tools"]):
                            self.fail(f"Architecture violation in {rel_path}: 'protocol' module imports application module '{mod}'")
                            
                        # EventBus must not import WebSocket code
                        if "event_bus.py" in rel_path and ("websockets" in mod or "websocket_server" in mod):
                            self.fail(f"Architecture violation in {rel_path}: EventBus imports websocket code '{mod}'")

    def test_no_magic_strings_for_events_and_packets(self):
        """
        Ensure event_bus.publish() and send_envelope() calls do not use raw string literals.
        """
        for root, _, files in os.walk(self.root_dir):
            for file in files:
                if not file.endswith(".py"):
                    continue
                    
                file_path = os.path.join(root, file)
                rel_path = os.path.relpath(file_path, self.root_dir)
                
                # Exclude tests/protocols/compat wrappers/test runners
                if any(rel_path.startswith(p) for p in ["tests", "protocol", "config.py"]) or rel_path == "exit_test_runner.py":
                    continue
                    
                with open(file_path, "r", encoding="utf-8") as f:
                    try:
                        node = ast.parse(f.read(), filename=file_path)
                    except SyntaxError:
                        continue
                        
                for subnode in ast.walk(node):
                    if isinstance(subnode, ast.Call):
                        # Detect call name
                        func_name = None
                        if isinstance(subnode.func, ast.Attribute):
                            func_name = subnode.func.attr
                        elif isinstance(subnode.func, ast.Name):
                            func_name = subnode.func.id
                            
                        # Check event_bus.publish calls
                        if func_name == "publish":
                            # The first argument must not be a string constant (ast.Constant / ast.Str)
                            if subnode.args:
                                first_arg = subnode.args[0]
                                if isinstance(first_arg, (ast.Constant, ast.Str)):
                                    self.fail(f"Architecture violation in {rel_path}: event_bus.publish() uses raw string '{getattr(first_arg, 'value', getattr(first_arg, 's', ''))}' instead of EventType Enum")
                                    
                        # Check send_envelope calls
                        if func_name == "send_envelope":
                            # The third argument (packet_type) must not be a string constant
                            if len(subnode.args) >= 3:
                                third_arg = subnode.args[2]
                                if isinstance(third_arg, (ast.Constant, ast.Str)):
                                    self.fail(f"Architecture violation in {rel_path}: send_envelope() uses raw string '{getattr(third_arg, 'value', getattr(third_arg, 's', ''))}' instead of PacketType Enum")

if __name__ == "__main__":
    unittest.main()
