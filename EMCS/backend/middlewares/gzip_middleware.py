from fastapi import FastAPI
from starlette.middleware.gzip import GZipMiddleware
from starlette.types import ASGIApp, Receive, Scope, Send

# SSE 路径前缀白名单：这些路径不能被 gzip 压缩，否则 zlib 内部缓冲会把
# 逐字 delta 攒成一次性块吐给浏览器，破坏流式体验（评审裁决 2026-07-20；
# curl 默认不带 Accept-Encoding 所以之前测不出来）。
_SSE_PATH_PREFIXES: tuple[str, ...] = ('/agent/chat',)


class ConditionalGZipMiddleware(GZipMiddleware):
    """透传 SSE 路径的 GZip 中间件；其它请求走标准 gzip。"""

    def __init__(self, app: ASGIApp, minimum_size: int = 1000, compresslevel: int = 9) -> None:
        super().__init__(app, minimum_size=minimum_size, compresslevel=compresslevel)
        # GZipMiddleware.__init__ 已保存 self.app；此处冗余引用便于阅读
        self._raw_app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope['type'] == 'http':
            path = scope.get('path', '') or ''
            if any(path.startswith(prefix) for prefix in _SSE_PATH_PREFIXES):
                # 直接透传给下游 app，不套 GZipResponder，杜绝 zlib 缓冲
                await self._raw_app(scope, receive, send)
                return
        await super().__call__(scope, receive, send)


def add_gzip_middleware(app: FastAPI) -> None:
    """
    添加gzip压缩中间件（SSE 路径自动透传）

    :param app: FastAPI对象
    :return:
    """
    app.add_middleware(ConditionalGZipMiddleware, minimum_size=1000, compresslevel=9)
