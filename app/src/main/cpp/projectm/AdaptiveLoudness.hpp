#pragma once

#include "Audio/AudioConstants.hpp"
#include "BeatAnalysisConfig.hpp"
#include <projectM-4/projectM_cxx_export.h>
#include <array>
#include <cstdint>

namespace libprojectM {
namespace Audio {

// Drop-in implementation of projectM's internal Loudness interface. All state belongs to
// the engine instance, so changing presets does not reset the learned audio levels.
class PROJECTM_CXX_EXPORT Loudness
{
public:
    enum class Band : std::uint8_t { Bass = 0, Middles = 1, Treble = 2 };
    explicit Loudness(Band band);
    void Update(const std::array<float, SpectrumSamples>& spectrum, double elapsed, uint32_t frame);
    auto CurrentRelative() const -> float { return m_relative; }
    auto AverageRelative() const -> float { return m_attenuated; }

private:
    std::array<float, SpectrumSamples> m_weights{};
    std::array<float, SpectrumSamples> m_inverseEqualizer{};
    std::array<float, SpectrumSamples> m_previous{};
    float m_weightSum{};
    double m_baseline{};
    double m_fluxMean{};
    double m_fluxVariance{};
    float m_relative{};
    float m_attenuated{};
    bool m_initialized{};
};

} // namespace Audio
} // namespace libprojectM
