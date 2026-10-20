// 环境类型声明（vite/client 已含 .vue 声明之外的部分；此处补 import 断言用）
/// <reference types="vite/client" />

/** 构建时注入的预设公共服务器地址（vite define；未注入为空串） */
declare const __HYPERMUX_PUBLIC_SERVER__: string
