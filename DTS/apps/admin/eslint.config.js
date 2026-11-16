// @ts-check
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import eslintConfigPrettier from 'eslint-config-prettier'

export default tseslint.config(
  // 全局忽略
  { ignores: ['dist/**', 'node_modules/**', '*.output', '.vite/**'] },

  // JS 推荐规则
  js.configs.recommended,

  // TS 推荐规则
  ...tseslint.configs.recommended,

  // Vue 推荐规则（essential 级别，避免过度严格）
  ...pluginVue.configs['flat/essential'],

  // Vue + TS
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
      },
    },
  },

  // 项目级规则微调
  {
    rules: {
      // Vue 组件 props/emits 定义放在 script setup
      'vue/multi-word-component-names': 'off',
      // 允许 any 的临时使用（后续逐步收紧）
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    },
  },

  // Prettier 兼容（关闭与 Prettier 冲突的格式规则）
  eslintConfigPrettier,
)
