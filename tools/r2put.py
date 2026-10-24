"""PUT one file into an R2 bucket over the S3 API (SigV4, standard library only).
Usage: r2put.py <local file> <object key> <content type>; credentials from R2_KEY_ID / R2_SECRET."""
import datetime
import hashlib
import hmac
import os
import sys
import urllib.parse
import urllib.request

ACCOUNT = "3053ed04669460fe739b1ab8608d9ac5"
BUCKET = "releases"
HOST = ACCOUNT + ".r2.cloudflarestorage.com"


def sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def main():
    path, key, ctype = sys.argv[1], sys.argv[2], sys.argv[3]
    method = sys.argv[4] if len(sys.argv) > 4 else "PUT"
    access, secret = os.environ["R2_KEY_ID"], os.environ["R2_SECRET"]
    body = open(path, "rb").read() if method == "PUT" else b""
    payload = hashlib.sha256(body).hexdigest()
    now = datetime.datetime.now(datetime.timezone.utc)
    amz, day = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    uri = "/" + BUCKET + "/" + urllib.parse.quote(key)
    headers = {"host": HOST, "x-amz-content-sha256": payload, "x-amz-date": amz}
    if method == "PUT":
        headers["content-type"] = ctype
    signed = ";".join(sorted(headers))
    canonical = "\n".join([method, uri, "", "".join(k + ":" + headers[k] + "\n" for k in sorted(headers)), signed, payload])
    scope = day + "/auto/s3/aws4_request"
    to_sign = "\n".join(["AWS4-HMAC-SHA256", amz, scope, hashlib.sha256(canonical.encode()).hexdigest()])
    k = sign(sign(sign(sign(("AWS4" + secret).encode(), day), "auto"), "s3"), "aws4_request")
    signature = hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request("https://" + HOST + uri, data=body if method == "PUT" else None, method=method)
    for h, v in headers.items():
        if h != "host":
            req.add_header(h, v)
    req.add_header("Authorization", "AWS4-HMAC-SHA256 Credential=" + access + "/" + scope
                   + ", SignedHeaders=" + signed + ", Signature=" + signature)
    with urllib.request.urlopen(req) as r:
        print(method, r.status, key, r.headers.get("ETag"), r.headers.get("Content-Length"))


main()
