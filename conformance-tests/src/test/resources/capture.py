import json
from mitmproxy import http


def response(flow: http.HTTPFlow) -> None:
    entry = [
        flow.request.method,
        flow.request.pretty_url,
        dict(flow.request.headers),
        flow.request.get_text(strict=False),
        flow.response.status_code,
        dict(flow.response.headers),
        flow.response.get_text(strict=False),
    ]

    print(json.dumps(entry, ensure_ascii=False))
