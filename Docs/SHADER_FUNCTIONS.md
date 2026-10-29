# Additional MilkDrop shader functions

The renderer accepts the functions listed in the supplied Undocumented Shader
Functions reference, including the existing HLSL intrinsics and these extensions:

- Scalar/vector `all`, `any`, `frexp` and `isfinite`.
- `degrees`, `radians`, `determinant`, `lit`, `sinh`, `tanh` and `trunc`.
- Scalar through four-dimensional continuous gradient `noise`, using quintic
  interpolation. Its gradient seed is specific to this renderer; matching values
  across unrelated DirectX drivers is not guaranteed.
- Correct vector results for `fwidth`, `isinf` and `isnan`, and scalar/vector
  `modf` output parameters with signed fractional values.
- `round` uses ties-to-even rounding, including negative halfway cases.
- `tex1D`, `tex1Dgrad`, `tex1Dbias`, `tex1Dlod` and `tex1Dproj` use a 2D image
  sampled at vertical coordinate 0.5. GLES has no native 1D texture target.
- Gradient overloads of `tex2D`, `tex3D` and `texCUBE`; explicit gradient and
  projection variants; 3D bias and explicit mip-level sampling.

Texture functions require the matching sampler and texture target. Cube sampling
syntax is supported, but ordinary JPG/PNG images loaded by the current texture
manager are 2D textures, not cube maps. This change does not add a cube-map asset
loader. The built-in volume noise textures provide 3D sampling inputs.

Explicit LOD and bias use the mip levels actually present in the source texture.
Derivative functions and gradient-based sampling run in fragment shaders; results
inside divergent branches can vary by GPU, as with DirectX shaders.

Verification uses temporary native shader fixtures outside the tracked sources.
No test files are packaged or added to the repository.
