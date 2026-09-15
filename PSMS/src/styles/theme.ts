import type { ThemeConfig } from 'antd';

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#409EFF',
    colorInfo: '#409EFF',
    colorSuccess: '#67C23A',
    colorWarning: '#E6A23C',
    colorError: '#F56C6C',
    colorText: '#303133',
    colorTextSecondary: '#909399',
    colorBorder: '#DCDFE6',
    colorBorderSecondary: '#E4E7ED',
    colorBgLayout: '#F5F7FA',
    colorBgContainer: '#FFFFFF',
    borderRadius: 6,
    borderRadiusLG: 10,
    controlHeight: 36,
    boxShadowSecondary: '0 10px 28px rgba(15, 23, 42, 0.08)',
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", Arial, sans-serif',
  },
  components: {
    Card: {
      headerBg: '#FAFBFC',
    },
    Menu: {
      darkItemBg: '#24364A',
      darkSubMenuItemBg: '#1F3043',
      darkItemSelectedBg: '#409EFF',
    },
    Button: {
      primaryShadow: '0 5px 14px rgba(64, 158, 255, 0.18)',
    },
  },
};