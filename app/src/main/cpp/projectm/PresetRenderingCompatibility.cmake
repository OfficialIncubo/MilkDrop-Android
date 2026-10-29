# Declaration cleanup must never match words inside comments. In particular,
# "// apply the main sampler" used to consume the following texture lookup,
# because the declaration regex permits a newline after the word sampler.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[    // Remove previous shader declarations]=]
    [=[    sourcePreprocessed = Utils::StripComments(sourcePreprocessed);

    // Remove previous shader declarations]=]
)

# D3D9 places pixel centers at integer coordinates; GLES uses half integers.
# Translate custom waves by half a framebuffer pixel so a wave at x=0 lands
# on the first column rather than outside its line rasterization coverage.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomWaveform.cpp
    [=[* m_presetState.renderContext.invAspectX),]=]
    [=[* m_presetState.renderContext.invAspectX + 1.0 / m_presetState.renderContext.viewportSizeX),]=]
    [=[* m_presetState.renderContext.invAspectY));]=]
    [=[* m_presetState.renderContext.invAspectY - 1.0 / m_presetState.renderContext.viewportSizeY));]=]
)

# HLSL promotes the two branches of a conditional before assigning its result.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[            conditionalExpression->expressionType  = expression1->expressionType;]=]
    [=[            conditionalExpression->expressionType = expression1->expressionType;
            HLSLType promoted;
            if (GetBinaryOpResultType(HLSLBinaryOp_Add, expression1->expressionType,
                                      expression2->expressionType, promoted))
            {
                conditionalExpression->expressionType = promoted;
            }]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[OutputExpression( conditionalExpression->trueExpression, dstType );]=]
    [=[OutputExpression( conditionalExpression->trueExpression, &conditionalExpression->expressionType );]=]
    [=[OutputExpression( conditionalExpression->falseExpression, dstType );]=]
    [=[OutputExpression( conditionalExpression->falseExpression, &conditionalExpression->expressionType );]=]
)

# BeatDrop resolves random slots separately for each compiled shader. Preserve
# slot and prefix aliases within that stage, without coupling warp to composite.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[randomSlot <= 15]=] [=[randomSlot <= 99]=]
    [=[    std::locale loc;]=]
    [=[    std::locale loc;
    std::map<int, Renderer::TextureSamplerDescriptor> randomTextureDescriptors;
    std::map<std::string, Renderer::TextureSamplerDescriptor> prefixedRandomTextures;]=]
    [=[presetState.randomTextureDescriptors]=] [=[randomTextureDescriptors]=]
    [=[presetState.prefixedRandomTextures]=] [=[prefixedRandomTextures]=]
)

# The reference packs border colors through D3DCOLOR_RGBA, truncating and
# wrapping each component to a byte. GLES otherwise clamps negative RGB to black.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Border.cpp
    [=[#include <Renderer/BlendMode.hpp>]=]
    [=[#include <Renderer/BlendMode.hpp>
#include <cmath>]=]
    [=[            glVertexAttrib4f(1, r, g, b, a);]=]
    [=[            const auto packedChannel = [](float value) {
                if (!std::isfinite(value)) return 0.0f;
                const float scaled = value * 255.0f;
                if (!std::isfinite(scaled)) return 0.0f;
                float byte = static_cast<float>(std::fmod(std::trunc(static_cast<double>(scaled)), 256.0));
                if (byte < 0.0f) byte += 256.0f;
                return byte / 255.0f;
            };
            glVertexAttrib4f(1, packedChannel(r), packedChannel(g), packedChannel(b), packedChannel(a));]=]
)

# Echo/gamma vertex colors use the same packed-byte behavior as borders in D3D.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/VideoEcho.cpp
    [=[#include <Renderer/BlendMode.hpp>]=]
    [=[#include <Renderer/BlendMode.hpp>
#include <cmath>]=]
    [=[namespace MilkdropPreset {]=]
    [=[namespace MilkdropPreset {
static float PackedEchoChannel(float value)
{
    const float scaled = value * 255.0f;
    if (!std::isfinite(scaled)) return 0.0f;
    float byte = static_cast<float>(std::fmod(std::trunc(static_cast<double>(scaled)), 256.0));
    if (byte < 0.0f) byte += 256.0f;
    return byte / 255.0f;
}]=]
    [=[gamma * mix * m_shade[vertex][0],]=] [=[PackedEchoChannel(gamma * mix * m_shade[vertex][0]),]=]
    [=[gamma * mix * m_shade[vertex][1],]=] [=[PackedEchoChannel(gamma * mix * m_shade[vertex][1]),]=]
    [=[gamma * mix * m_shade[vertex][2],]=] [=[PackedEchoChannel(gamma * mix * m_shade[vertex][2]),]=]
    [=[mix * m_shade[vertex][0],]=] [=[PackedEchoChannel(mix * m_shade[vertex][0]),]=]
    [=[mix * m_shade[vertex][1],]=] [=[PackedEchoChannel(mix * m_shade[vertex][1]),]=]
    [=[mix * m_shade[vertex][2],]=] [=[PackedEchoChannel(mix * m_shade[vertex][2]),]=]
    [=[gamma * m_shade[vertex][0],]=] [=[PackedEchoChannel(gamma * m_shade[vertex][0]),]=]
    [=[gamma * m_shade[vertex][1],]=] [=[PackedEchoChannel(gamma * m_shade[vertex][1]),]=]
    [=[gamma * m_shade[vertex][2],]=] [=[PackedEchoChannel(gamma * m_shade[vertex][2]),]=]
)

# Keep legacy math intrinsics explicit on GLES feedback buffers.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[#include "MilkdropShader.hpp"]=]
    [=[#include "MilkdropShader.hpp"
#include "ShaderMathCompatibility.hpp"]=]
    [=[    // Preprocess define macros]=]
    [=[    program = milkdrop::CompatibleShaderMath(program);
    // Preprocess define macros]=]
)

# BeatDrop blurs the current post-warp image before shapes and waves. Using the
# previous frame misaligns composite bump-map derivatives with sampler_main.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[const auto warpedImage = m_framebuffer.GetColorAttachmentTexture(m_previousFrameBuffer, 0);]=]
    [=[const auto warpedImage = m_framebuffer.GetColorAttachmentTexture(m_currentFrameBuffer, 0);]=]
)
