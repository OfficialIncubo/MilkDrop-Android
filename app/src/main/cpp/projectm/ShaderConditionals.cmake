# Extend the already-patched parser copy without modifying the projectM submodule.
target_include_directories(hlslparser PRIVATE ${CMAKE_CURRENT_LIST_DIR})
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[#include "HLSLParser.h"]=]
    [=[#include "HLSLParser.h"
#include "ShaderConditional.hpp"]=]
    [=[            while (m_tokenizer.GetToken() != HLSLToken_IntLiteral && m_tokenizer.GetToken() != HLSLToken_EndOfLine)
            {
                m_tokenizer.Next(false);
            }

            if (m_tokenizer.GetToken() == HLSLToken_IntLiteral)
            {
                isCodeActive.push(m_tokenizer.GetInt() != 0);
            }
            else
            {
                m_tokenizer.Error("#if evaluation failed: not an integer");
                return false;
            }]=]
    [=[            const char* conditionStart = m_tokenizer.getCurrentPos();
            while (m_tokenizer.GetToken() != HLSLToken_EndOfLine &&
                   m_tokenizer.GetToken() != HLSLToken_EndOfStream)
            {
                m_tokenizer.Next(false);
            }
            const std::string condition(conditionStart, m_tokenizer.getCurrentPos());
            bool enabled = false;
            if (isCodeActive.top() && !milkdrop::ShaderConditional::Evaluate(condition,
                [this](const std::string& name) -> const char* {
                    for (int i = m_macros.GetSize() - 1; i >= 0; --i)
                        if (String_Equal(m_macros[i]->name, name.c_str())) return m_macros[i]->value;
                    return nullptr;
                }, enabled))
            {
                m_tokenizer.Error("#if evaluation failed: unsupported condition");
                return false;
            }
            isCodeActive.push(isCodeActive.top() && enabled);]=]
    [=[            // Invert stack state
            bool state = isCodeActive.top();
            isCodeActive.pop();
            isCodeActive.push(!state);]=]
    [=[            if (isCodeActive.size() <= 1) return false;
            const bool state = isCodeActive.top();
            isCodeActive.pop();
            isCodeActive.push(isCodeActive.top() && !state);]=]
    [=[        else if (m_tokenizer.GetToken() == HLSLToken_PreprocessorEndif)
        {
            isCodeActive.pop();]=]
    [=[        else if (m_tokenizer.GetToken() == HLSLToken_PreprocessorEndif)
        {
            if (isCodeActive.size() <= 1) return false;
            isCodeActive.pop();]=]
    [=[            while(m_tokenizer.GetToken() != HLSLToken_EndOfLine)]=]
    [=[            while(m_tokenizer.GetToken() != HLSLToken_EndOfLine &&
                  m_tokenizer.GetToken() != HLSLToken_EndOfStream)]=]
)
