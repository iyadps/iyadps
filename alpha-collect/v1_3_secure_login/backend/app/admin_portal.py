from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse

router=APIRouter()

@router.get("/admin", include_in_schema=False)
def admin_page():
    return FileResponse(Path(__file__).parent/"static"/"admin.html",
                        media_type="text/html",
                        headers={"Cache-Control":"no-store",
                                 "Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'",
                                 "X-Content-Type-Options":"nosniff",
                                 "Referrer-Policy":"no-referrer"})
