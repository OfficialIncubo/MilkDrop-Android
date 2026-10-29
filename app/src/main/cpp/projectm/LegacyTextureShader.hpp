#pragma once
#include <string>

namespace milkdrop {

// D3D9's fixed-function shape pass modulates texture RGB but selects diffuse
// alpha. Feedback texture alpha is render-target bookkeeping, not opacity.
inline std::string LegacyTextureShader(std::string source)
{
    const std::string original = "color = fragment_color * texture(texture_sampler, fragment_texture.st);";
    const auto at = source.find(original);
    if (at != std::string::npos)
    {
        source.replace(at, original.size(),
            "color = vec4(fragment_color.rgb * texture(texture_sampler, fragment_texture.st).rgb, fragment_color.a);");
    }
    return source;
}

} // namespace milkdrop
