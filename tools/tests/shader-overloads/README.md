# HLSL exponent overload regression presets

Render each preset without a transition and read the output framebuffer.
Every pixel should have the following RGB values in an 8-bit framebuffer
(allow one count for driver rounding):

| Preset | Expected RGB | DirectX behavior |
| --- | --- | --- |
| vector1-pow.milk | 10, 10, 10 | float1 exponent selects one component, then broadcasts the result |
| scalar-pow.milk | 10, 41, 163 | float exponent broadcasts before component-wise power |

Both use `e+e` to check that the distinction survives expression evaluation.
The expected overload selection was verified with Microsoft D3DCompile targeting
ps_3_0, including the same float1 expression used by cope - digital sea.
The presets belong to the test suite and are not bundled in the app.
