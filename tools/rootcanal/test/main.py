from importlib import resources
from pathlib import Path
import importlib
import tempfile

# Python is not able to load the module lib_rootcanal_python3.so
# when the test target is configured with embedded_launcher: true.
# This code loads the file to a temporary directory and adds the
# path to the sys lookup.
with tempfile.TemporaryDirectory() as cache:
    with (Path('lib_rootcanal_python3.so').open('rb') as fin,
          Path(cache, 'lib_rootcanal_python3.so').open('wb') as fout):
        fout.write(fin.read())
    sys.path.append(cache)
    import lib_rootcanal_python3

import unittest

tests = [
  'LL.DDI.ADV.BV_01_C',
  'LL.DDI.ADV.BV_02_C',
  'LL.DDI.ADV.BV_03_C',
  'LL.DDI.ADV.BV_04_C',
  'LL.DDI.ADV.BV_05_C',
  'LL.DDI.ADV.BV_06_C',
  'LL.DDI.ADV.BV_07_C',
  'LL.DDI.ADV.BV_08_C',
  'LL.DDI.SCN.BV_13_C',
  'LL.DDI.SCN.BV_14_C',
  'LL.DDI.SCN.BV_18_C',
]

if __name__ == "__main__":
    suite = unittest.TestSuite()
    for test in tests:
        if len(sys.argv) > 1 and test not in sys.argv:
            continue
        module = importlib.import_module(f'test.{test}')
        suite.addTest(unittest.defaultTestLoader.loadTestsFromModule(module))
    unittest.TextTestRunner(verbosity=3).run(suite)
