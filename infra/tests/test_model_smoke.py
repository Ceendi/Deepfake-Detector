"""Model publication must check remote availability even with verified cached weights."""

import importlib.util
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]


class ModelSmokeTest(unittest.TestCase):
    def test_every_pinned_file_is_checked_remotely(self):
        spec = importlib.util.spec_from_file_location('model_smoke', ROOT / 'infra/ci/model_smoke.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        manifest = {'repo': 'owner/model', 'revision': 'a' * 40,
                    'files': {'one.onnx': 'b' * 64, 'two.pt': 'c' * 64}}
        metadata = Mock()
        url = Mock(side_effect=lambda **kwargs: kwargs)
        module.check_remote_files(manifest, url=url, metadata=metadata)
        self.assertEqual(2, metadata.call_count)
        for call, filename in zip(url.call_args_list, manifest['files']):
            self.assertEqual({'repo_id': 'owner/model', 'revision': 'a' * 40,
                              'filename': filename}, call.kwargs)
        metadata.side_effect = RuntimeError('repository unavailable')
        with self.assertRaisesRegex(RuntimeError, 'unavailable'):
            module.check_remote_files(manifest, url=url, metadata=metadata)


if __name__ == '__main__':
    unittest.main(verbosity=2)
