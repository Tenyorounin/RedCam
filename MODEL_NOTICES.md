# Model attribution

Portrait depth estimation uses the 256-pixel, UINT8-exported Depth Anything V2 Small model (`fused_model_uint8_256.onnx`). The build downloads this file from the `model-v2` release of [Depth-Anything-Android](https://github.com/shubham0204/Depth-Anything-Android), which packages an Android ONNX export. The model runs locally on the device.

Depth Anything V2 Small is identified by its authors as Apache-2.0 licensed. The upstream Android inference project is also Apache-2.0 licensed. Consult their full licenses and notices before redistributing the model or this prototype.

The model estimates relative depth ordering, not calibrated distance in metres. It can misorder thin structures, reflections, transparent objects, and overlapping edges.
