"""All video inference sessions share the configured CPU thread budget."""

import sys
from unittest.mock import MagicMock


def test_configured_threads_cover_torch_and_all_onnx_sessions(load_inference, monkeypatch):
    module = load_inference()
    monkeypatch.setattr(module, "ensure_model_files", MagicMock())
    monkeypatch.setattr(module, "ONNX_INTRA_THREADS", 3)
    face_module = MagicMock()
    monkeypatch.setitem(sys.modules, "insightface", MagicMock())
    monkeypatch.setitem(sys.modules, "insightface.app", face_module)
    model = module.VideoInference()
    model._ensure_face_app()
    module.torch.set_num_threads.assert_called_once_with(3)
    assert len(module.ort.InferenceSession.call_args_list) == 2
    for call in module.ort.InferenceSession.call_args_list + [face_module.FaceAnalysis.call_args]:
        assert call.kwargs["sess_options"].intra_op_num_threads == 3
        assert call.kwargs["providers"] == ["CPUExecutionProvider"]
    face_module.FaceAnalysis.return_value.prepare.assert_called_once_with(
        ctx_id=-1, det_size=(module.DET_SIZE, module.DET_SIZE)
    )


def test_pinned_weights_are_ready_before_loading(load_inference, monkeypatch):
    module = load_inference()
    monkeypatch.setattr(module, "ensure_model_files", MagicMock())
    module.VideoInference()
    module.ensure_model_files.assert_called_once_with(module.MODELS_MANIFEST, module.MODEL_DIR)
    assert module.ort.InferenceSession.call_args_list[0].args[0] == module.BACKBONE_ONNX_PATH
