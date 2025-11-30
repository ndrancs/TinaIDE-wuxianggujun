--!A cross-platform build utility based on Lua
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- Copyright (C) 2015-present, Xmake Open Source Community.
--
-- TinaIDE override:
--   The upstream script launches `xmake lua cleaner.lua` via `process.openv`, which ends up
--   forking `/system/bin/app_process` on Android and crashes inside the sandboxed app.
--   We keep the same cleanup logic but execute it inline so no external process is spawned.

-- imports
import("core.project.project")
import("core.package.package")

local function run_cleanup_tasks()
    -- clean up the temporary files at last 30 days, @see os.tmpdir()
    local parentdir = path.directory(os.tmpdir())
    for day = 1, 30 do
        local tmpdir = path.join(parentdir, os.date("%y%m%d", os.time() - day * 24 * 3600))
        if os.isdir(tmpdir) then
            print("cleanup %s ..", tmpdir)
            os.tryrm(tmpdir)
        end
    end

    -- clean up the temporary files of project at last 30 days, @see project.tmpdir()
    if os.isfile(os.projectfile()) then
        local projectdir = path.directory(project.tmpdir())
        for day = 1, 30 do
            local tmpdir = path.join(projectdir, os.date("%y%m%d", os.time() - day * 24 * 3600))
            if os.isdir(tmpdir) then
                print("cleanup %s ..", tmpdir)
                os.tryrm(tmpdir)
            end
        end
    end

    -- clean up the previous month package cache files, @see package.cachedir()
    local cachedir = path.join(package.cachedir({rootonly = true}), os.date("%y%m", os.time() - 31 * 24 * 3600))
    if os.isdir(cachedir) and cachedir ~= package.cachedir() then
        print("cleanup %s ..", cachedir)
        os.tryrm(cachedir)
    end
end

-- clean up temporary files once a day (inline, without spawning app_process)
function cleanup()
    local markfile = path.join(os.tmpdir(), "cleanup", os.date("%y%m%d") .. ".mark")
    if os.isfile(markfile) then
        return
    end

    io.writefile(markfile, "ok")
    local ok, err = xpcall(run_cleanup_tasks, debug.traceback)
    if not ok then
        -- allow retry next run if cleanup fails
        os.tryrm(markfile)
        print("warning: cleanup task failed: %s", err)
    end
end

-- entry point for `xmake lua cleaner.lua`
function main()
    run_cleanup_tasks()
end
