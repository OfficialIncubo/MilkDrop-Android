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
