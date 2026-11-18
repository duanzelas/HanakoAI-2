package fun.kirari.hanako.platform.capture.shizuku;

interface IShizukuShellService {
    byte[] exec(in String[] command);

    /**
     * 以 shell 身份整屏捕获，并请求把 FLAG_SECURE 的 secure 层一起合成。
     * 返回 JPEG 字节；当前系统不支持或失败时返回空数组，调用方需回退到 exec("screencap")。
     *
     * maxLongEdge 为长边上限（px），0 表示不限制。
     */
    byte[] captureSecureDisplay(int maxLongEdge);
}
