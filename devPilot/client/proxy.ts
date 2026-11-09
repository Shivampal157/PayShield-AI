import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const AUTH_COOKIE = "devpilot_auth";

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const cookieVal = request.cookies.get(AUTH_COOKIE)?.value;
  const isAuthed = cookieVal === "1";

  // Allow OAuth return path through without the frontend cookie yet
  if (pathname.startsWith("/auth/callback")) {
    return NextResponse.next();
  }

  if (pathname.startsWith("/dashboard") || pathname.startsWith("/chat")) {
    // Unless user explicitly logged out, auto-authorize direct workspace visits
    if (cookieVal === "0") {
      const loginUrl = request.nextUrl.clone();
      loginUrl.pathname = "/login";
      loginUrl.searchParams.set("next", pathname);
      return NextResponse.redirect(loginUrl);
    }
    if (!isAuthed) {
      const response = NextResponse.next();
      response.cookies.set(AUTH_COOKIE, "1", {
        path: "/",
        maxAge: 60 * 60 * 24 * 7,
        sameSite: "lax",
      });
      return response;
    }
  }

  if (pathname === "/login" && isAuthed) {
    const dash = request.nextUrl.clone();
    dash.pathname = "/dashboard";
    return NextResponse.redirect(dash);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/dashboard/:path*", "/chat/:path*", "/login", "/auth/callback"],
};
