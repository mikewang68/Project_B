import type { ThemeConfig } from 'antd';

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#176b8d',
    colorInfo: '#176b8d',
    colorSuccess: '#2f7d58',
    colorWarning: '#b26b20',
    colorError: '#b83c42',
    colorText: '#142b3a',
    colorTextSecondary: '#5d7180',
    colorBorder: '#cbd9e2',
    colorBorderSecondary: '#dfe8ee',
    colorBgLayout: '#EEF3F6',
    colorBgContainer: '#FFFFFF',
    borderRadius: 6,
    borderRadiusLG: 10,
    controlHeight: 36,
    boxShadowSecondary: '0 10px 28px rgba(20, 43, 58, 0.08)',
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", Arial, sans-serif',
  },
  components: {
    Card: {
      headerBg: '#F9FBFC',
    },
    Menu: {
      darkItemBg: '#071C2B',
      darkSubMenuItemBg: '#071C2B',
      darkItemSelectedBg: '#176B8D',
    },
    Button: {
      primaryShadow: '0 5px 14px rgba(23, 107, 141, 0.18)',
    },
  },
};
