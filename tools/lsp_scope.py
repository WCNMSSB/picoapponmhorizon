#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 周-zhou (WCNMSSB) and DeepSeek
#
# This file is part of picoapponmhorizon.
#
"""Add a package to the LSPosed module scope from the command line.

LSPosed keeps its configuration in a SQLite database on the device
(/data/adb/lspd/config/modules_config.db). The manager UI is the supported way
to edit it, but a one-command installer needs this as well, so this script edits
a copy of that database and the caller pushes it back and restarts lspd.

  python tools/lsp_scope.py --db modules_config.db --module com.pico.xr.compat.lsp \
      --app com.example.app [--remove]
"""
from __future__ import annotations

import argparse
import sqlite3
import sys


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--module", required=True)
    ap.add_argument("--app", required=True)
    ap.add_argument("--user", type=int, default=0)
    ap.add_argument("--remove", action="store_true")
    ap.add_argument("--check", action="store_true", help="only report")
    args = ap.parse_args(argv)

    con = sqlite3.connect(args.db)
    cur = con.cursor()
    row = cur.execute(
        "select 1 from scope where module_pkg_name=? and app_pkg_name=? and user_id=?",
        (args.module, args.app, args.user)).fetchone()

    if args.check:
        print("in scope" if row else "not in scope")
        return 0

    if args.remove:
        if row:
            cur.execute(
                "delete from scope where module_pkg_name=? and app_pkg_name=? and user_id=?",
                (args.module, args.app, args.user))
            con.commit()
            print("removed from scope")
        else:
            print("was not in scope")
        return 0

    if row:
        print("already in scope")
        return 0

    cur.execute("insert into scope(module_pkg_name, app_pkg_name, user_id) values (?,?,?)",
                (args.module, args.app, args.user))
    con.commit()
    print("added to scope")
    return 0


if __name__ == "__main__":
    sys.exit(main())
