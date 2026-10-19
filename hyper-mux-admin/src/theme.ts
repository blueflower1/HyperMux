// naive-ui 全局主题覆写：橙色品牌 + 大圆角（事实源见 style.css 设计令牌）
import type { GlobalThemeOverrides } from 'naive-ui'

export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#f5820d',
    primaryColorHover: '#ff9a3d',
    primaryColorPressed: '#d96f06',
    primaryColorSuppl: '#f5820d',
    infoColor: '#2f6fed',
    successColor: '#18a058',
    warningColor: '#f0a020',
    errorColor: '#d03050',
    borderRadius: '10px',
    fontFamily: 'inherit',
  },
  Button: {
    borderRadiusSmall: '8px',
    borderRadiusMedium: '10px',
  },
  Input: {
    borderRadiusSmall: '8px',
    borderRadiusMedium: '10px',
    focusShadow: '0 0 0 3px rgba(245, 130, 13, 0.14)',
    peerFocusTextColor: '$primary-color',
  },
  Card: {
    borderRadius: '14px',
    titleFontWeight: '700',
  },
  Modal: {
    borderRadius: '16px',
  },
  Tag: {
    borderRadiusMedium: '8px',
  },
  Tabs: {
    tabTextColor: '#5c6675',
    tabTextColorActive: '#f5820d',
    tabFontWeightActive: '600',
    lineColor: '#f5820d',
  },
  Statistic: {
    labelFontSize: '13px',
    valueFontSize: '30px',
  },
  Alert: {
    borderRadiusMedium: '10px',
  },
}
