#!/usr/bin/env julia
# Neuropublish neutrality proof (ADR 0001): a foreign producer that builds and
# publishes a bundle from documented ingredients only — the manifest vocabulary
# (modules/conformance/fixtures/reference/manifest.json and the JSON Schema),
# the byte profile, the digest rule (SHA-256 over the manifest bytes as
# written), the volume-grid and surface-vertices identity preimages (ADR 0005),
# the NIfTI-1 and GIFTI formats, and the HTTP upload/commit protocol. No
# Neuropublish code, no generated client.
#
# Usage:
#   julia producer.jl --out DIR                      # write the bundle only
#   julia producer.jl --out DIR --server URL --project WS/PROJ --token T \
#                     [--parent REVISION] [--message TEXT]
#
# Writes `manifest.json`, `manifest.sha256`, `assets/<id>.nii`, `assets/<id>.surf.gii`,
# `assets/<id>.func.gii`, and `oracle.json` (the shape, affine, and probe voxel values, and the
# surface vertices, faces, and field values, an independent reader must see; not part of the
# bundle). Prints `digest sha256:<hex>` for the bundle; with --server also prints
# `revision <id>`, `viewUrl <url>`, `server-digest sha256:<hex>` and one
# `rendition <asset> <status>` line per derived asset. Exits non-zero with a
# one-line `error: ...` message on any failure.

using SHA
using JSON3
using Downloads
using Base64

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

function parse_args(argv)
    opts = Dict{String,String}()
    i = 1
    while i <= length(argv)
        a = argv[i]
        startswith(a, "--") || fail("unexpected argument $a")
        i == length(argv) && fail("missing value for $a")
        opts[a[3:end]] = argv[i + 1]
        i += 2
    end
    haskey(opts, "out") || fail("--out DIR is required")
    if haskey(opts, "server")
        for k in ("project", "token")
            haskey(opts, k) || fail("--$k is required with --server")
        end
        occursin('/', opts["project"]) || fail("--project must be workspace/project")
    end
    opts
end

function fail(msg)
    println(stderr, "error: ", msg)
    exit(1)
end

# ---------------------------------------------------------------------------
# NIfTI-1: a 352-byte little-endian header (348 + 4 extension bytes), float32
# voxels (datatype 16, bitpix 32), vox_offset 352, qform and sform both set
# to the same RAS+ affine (sform_code = qform_code = 1, NIFTI_XFORM_SCANNER_ANAT).
# ---------------------------------------------------------------------------

const SHAPE = (16, 16, 12)
const SPACING = (2.0, 2.0, 2.0)
const ORIGIN = (-15.0, -15.0, -11.0)
# 1-based voxel indices whose values `oracle.json` records for an independent reader to verify
const PROBE_VOXELS = ((8, 8, 6), (4, 12, 6), (1, 1, 1), (16, 16, 12), (5, 9, 3))

function affine()
    [SPACING[1] 0.0 0.0 ORIGIN[1];
     0.0 SPACING[2] 0.0 ORIGIN[2];
     0.0 0.0 SPACING[3] ORIGIN[3];
     0.0 0.0 0.0 1.0]
end

# Write `v` little-endian into `buf` at 0-based byte offset `off`; returns the next offset.
function put!(buf::Vector{UInt8}, off::Int, v)
    b = reinterpret(UInt8, [htol(v)])
    buf[off+1:off+length(b)] = b
    off + length(b)
end

function nifti_bytes(data::Array{Float32,3})
    hdr = zeros(UInt8, 348)
    put!(hdr, 0, Int32(348))                               # sizeof_hdr
    p = put!(hdr, 40, Int16(3))                            # dim[0]
    for n in SHAPE
        p = put!(hdr, p, Int16(n))                         # dim[1..3]
    end
    for _ in 4:7
        p = put!(hdr, p, Int16(1))                         # dim[4..7]
    end
    put!(hdr, 70, Int16(16))                               # datatype NIFTI_TYPE_FLOAT32
    put!(hdr, 72, Int16(32))                               # bitpix
    p = put!(hdr, 76, Float32(1.0))                        # pixdim[0] (qfac)
    for s in SPACING
        p = put!(hdr, p, Float32(s))                       # pixdim[1..3]
    end
    put!(hdr, 108, Float32(352))                           # vox_offset
    put!(hdr, 112, Float32(1.0))                           # scl_slope
    put!(hdr, 116, Float32(0.0))                           # scl_inter
    put!(hdr, 123, UInt8(2 | 8))                           # xyzt_units: mm | sec
    put!(hdr, 252, Int16(1))                               # qform_code
    put!(hdr, 254, Int16(1))                               # sform_code
    p = 256
    for q in (0.0f0, 0.0f0, 0.0f0)
        p = put!(hdr, p, q)                                # quatern b c d (identity rotation)
    end
    for o in ORIGIN
        p = put!(hdr, p, Float32(o))                       # qoffset x y z
    end
    A = affine()
    p = 280
    for r in 1:3, c in 1:4
        p = put!(hdr, p, Float32(A[r, c]))                 # srow_x, srow_y, srow_z
    end
    hdr[345:348] = UInt8['n', '+', '1', 0]                 # magic
    io = IOBuffer()
    write(io, hdr)
    write(io, zeros(UInt8, 4))                             # extension flag: none
    # x-fastest voxel order: Julia column-major matches NIfTI's i-fastest layout
    for k in 1:SHAPE[3], j in 1:SHAPE[2], i in 1:SHAPE[1]
        write(io, htol(data[i, j, k]))
    end
    take!(io)
end

function synthetic(kind::Symbol)
    data = zeros(Float32, SHAPE)
    cx, cy, cz = (SHAPE .+ 1) ./ 2
    for k in 1:SHAPE[3], j in 1:SHAPE[2], i in 1:SHAPE[1]
        r2 = ((i - cx)^2 + (j - cy)^2 + (k - cz)^2) / 18
        g = exp(-r2)
        data[i, j, k] = if kind == :t1
            Float32(100 * (1 - 0.5 * g) * (r2 < 3 ? 1 : 0.4))
        elseif kind == :effect
            Float32(0.8 * g - 0.2 * exp(-((i - 4)^2 + (j - 12)^2 + (k - 6)^2) / 6))
        elseif kind == :t
            Float32(6.0 * g - 3.5 * exp(-((i - 4)^2 + (j - 12)^2 + (k - 6)^2) / 6))
        else
            Float32(5.5 * g - 3.2 * exp(-((i - 4)^2 + (j - 12)^2 + (k - 6)^2) / 6))
        end
    end
    data
end

# ---------------------------------------------------------------------------
# Surfaces: a unit icosahedron subdivided three times (642 vertices, 1280
# faces), scaled to 25 mm and offset -30 mm (left) / +30 mm (right) in x, in
# RAS+ mm. Written as GIFTI 1.0 (`.surf.gii`: POINTSET float32 + TRIANGLE
# int32; `.func.gii`: one float32 per vertex), Base64Binary little-endian.
# Vertex and face order is deterministic: subdivision walks the faces in order
# and appends each new midpoint when first needed.
# ---------------------------------------------------------------------------

const SURFACE_RADIUS = 25.0
const SURFACE_OFFSET = 30.0
const SURFACE_SUBDIVISIONS = 3
const SURFACE_SPACE = "synthetic-ico3"
const GIFTI_MEDIA_TYPE = "application/x-gifti"
const HEMISPHERES = (("left", "lh"), ("right", "rh"))

function icosphere(subdivisions)
    t = (1 + sqrt(5)) / 2
    verts = [[-1.0, t, 0.0], [1.0, t, 0.0], [-1.0, -t, 0.0], [1.0, -t, 0.0],
             [0.0, -1.0, t], [0.0, 1.0, t], [0.0, -1.0, -t], [0.0, 1.0, -t],
             [t, 0.0, -1.0], [t, 0.0, 1.0], [-t, 0.0, -1.0], [-t, 0.0, 1.0]]
    verts = [v ./ sqrt(sum(v .^ 2)) for v in verts]
    # 0-based vertex ordinals, outward counter-clockwise winding
    faces = [(0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11),
             (1, 5, 9), (5, 11, 4), (11, 10, 2), (10, 7, 6), (7, 1, 8),
             (3, 9, 4), (3, 4, 2), (3, 2, 6), (3, 6, 8), (3, 8, 9),
             (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1)]
    for _ in 1:subdivisions
        cache = Dict{Tuple{Int,Int},Int}()
        function midpoint(a, b)
            key = a < b ? (a, b) : (b, a)
            get!(cache, key) do
                m = (verts[a + 1] .+ verts[b + 1]) ./ 2
                push!(verts, m ./ sqrt(sum(m .^ 2)))
                length(verts) - 1
            end
        end
        next = Tuple{Int,Int,Int}[]
        for (a, b, c) in faces
            ab = midpoint(a, b); bc = midpoint(b, c); ca = midpoint(c, a)
            push!(next, (a, ab, ca)); push!(next, (b, bc, ab))
            push!(next, (c, ca, bc)); push!(next, (ab, bc, ca))
        end
        faces = next
    end
    verts, faces
end

# Little-endian bytes of a row-major matrix (GIFTI RowMajorOrder).
function rowmajor_bytes(m::AbstractMatrix)
    io = IOBuffer()
    for i in 1:size(m, 1), j in 1:size(m, 2)
        write(io, htol(m[i, j]))
    end
    take!(io)
end

function gifti_data_array(intent, datatype, dims, payload::Vector{UInt8}; metadata = (), transform = false)
    dimattrs = join(["Dim$(i - 1)=\"$(d)\"" for (i, d) in enumerate(dims)], " ")
    md = isempty(metadata) ? "<MetaData/>" :
        "<MetaData>" * join(["<MD><Name>$k</Name><Value>$v</Value></MD>" for (k, v) in metadata]) * "</MetaData>"
    xf = transform ?
        "<CoordinateSystemTransformMatrix><DataSpace>NIFTI_XFORM_SCANNER_ANAT</DataSpace>" *
        "<TransformedSpace>NIFTI_XFORM_SCANNER_ANAT</TransformedSpace>" *
        "<MatrixData>1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</MatrixData></CoordinateSystemTransformMatrix>" : ""
    "<DataArray Intent=\"$intent\" DataType=\"$datatype\" ArrayIndexingOrder=\"RowMajorOrder\" " *
    "Dimensionality=\"$(length(dims))\" $dimattrs Encoding=\"Base64Binary\" Endian=\"LittleEndian\">\n" *
    md * "\n" * xf * "\n<Data>" * base64encode(payload) * "</Data>\n</DataArray>\n"
end

function gifti_document(arrays)
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<GIFTI Version=\"1.0\" NumberOfDataArrays=\"$(length(arrays))\">\n" *
    "<MetaData/>\n" * join(arrays) * "</GIFTI>\n"
end

function gifti_surface(coords::Matrix{Float32}, faces, hemisphere)
    idx = Matrix{Int32}(undef, length(faces), 3)
    for (i, (a, b, c)) in enumerate(faces)
        idx[i, 1] = a; idx[i, 2] = b; idx[i, 3] = c
    end
    structure = hemisphere == "left" ? "CortexLeft" : "CortexRight"
    Vector{UInt8}(codeunits(gifti_document([
        gifti_data_array("NIFTI_INTENT_POINTSET", "NIFTI_TYPE_FLOAT32", size(coords), rowmajor_bytes(coords);
            metadata = (("AnatomicalStructurePrimary", structure), ("GeometricType", "Pial")), transform = true),
        gifti_data_array("NIFTI_INTENT_TRIANGLE", "NIFTI_TYPE_INT32", size(idx), rowmajor_bytes(idx);
            metadata = (("TopologicalType", "Closed"),)),
    ])))
end

function gifti_field(values::Vector{Float32}, name)
    io = IOBuffer()
    for v in values
        write(io, htol(v))
    end
    Vector{UInt8}(codeunits(gifti_document([
        gifti_data_array("NIFTI_INTENT_NONE", "NIFTI_TYPE_FLOAT32", (length(values),), take!(io);
            metadata = (("Name", name),)),
    ])))
end

# ---------------------------------------------------------------------------
# Identity helpers
# ---------------------------------------------------------------------------

digest(bytes) = "sha256:" * bytes2hex(sha256(bytes))

# ADR 0005 volume-grid/v1 structural fingerprint preimage: magic, six
# length-prefixed UTF-8 strings, three Int32 shape entries, sixteen Float64
# affine entries (row-major), all little-endian.
function volume_grid_fingerprint(descriptor_id, descriptor_version, space, convention, unit, layout)
    io = IOBuffer()
    write(io, UInt8['N', 'P', 'U', 'D', 'O', 'M', '1', 0])
    for s in (descriptor_id, descriptor_version, space, convention, unit, layout)
        b = Vector{UInt8}(codeunits(s))
        write(io, htol(Int32(length(b))))
        write(io, b)
    end
    for n in SHAPE
        write(io, htol(Int32(n)))
    end
    A = affine()
    for r in 1:4, c in 1:4
        v = A[r, c]
        write(io, htol(v == 0.0 ? 0.0 : v))                # no negative zero
    end
    digest(take!(io))
end

# ADR 0005 surface-vertices/v1 structural fingerprint preimage: magic, four
# length-prefixed UTF-8 strings (descriptor id, version, space, hemisphere),
# UInt64 vertex and face counts, then every triangle as three UInt32 vertex
# ordinals in face order; all little-endian. Coordinates are not part of it.
function surface_vertices_fingerprint(descriptor_id, descriptor_version, space, hemisphere, nverts, faces)
    io = IOBuffer()
    write(io, UInt8['N', 'P', 'U', 'D', 'O', 'M', '1', 0])
    for s in (descriptor_id, descriptor_version, space, hemisphere)
        b = Vector{UInt8}(codeunits(s))
        write(io, htol(Int32(length(b))))
        write(io, b)
    end
    write(io, htol(UInt64(nverts)))
    write(io, htol(UInt64(length(faces))))
    for (a, b, c) in faces
        write(io, htol(UInt32(a))); write(io, htol(UInt32(b))); write(io, htol(UInt32(c)))
    end
    digest(take!(io))
end

# ---------------------------------------------------------------------------
# Manifest (core 0.1 vocabulary as used by the reference bundle)
# ---------------------------------------------------------------------------

const VOLUME_GRID_SCHEMA = (
    id = "org.neuropublish.domain/volume-grid",
    version = "1.0",
    digest = "sha256:69c25b8868349828e41cd6d610ac619af118fb7b807b7306f706b727ed23dfb7",
)
const SURFACE_VERTICES_SCHEMA = (
    id = "org.neuropublish.domain/surface-vertices",
    version = "1.0",
    digest = "sha256:686d9b2e17090e776c3a876f470e83dc874638c10b3dc67a4e4fad27a31c9232",
)
const RECEIPT_SCHEMA = (
    id = "org.bbuchsbaum.fmrireg/analysis-receipt",
    version = "1.0",
    digest = "sha256:aa00000000000000000000000000000000000000000000000000000000000001",
)

function manifest(assets, surfaces)
    affine_rows = [[affine()[r, c] for c in 1:4] for r in 1:4]
    fingerprint = volume_grid_fingerprint(
        VOLUME_GRID_SCHEMA.id, VOLUME_GRID_SCHEMA.version,
        "MNI152NLin2009cAsym", "RAS+", "mm", "x-fastest")
    asset(id; extra...) = (
        id = id,
        digest = assets[id].digest,
        mediaType = assets[id].mediaType,
        size = assets[id].size,
        extra...,
    )
    # a surface representation: the producer's own per-vertex values on a declared surface, with
    # the provenance activity that produced them as the derivation receipt (the server never projects)
    surface_rep(id, hemi, code) = (
        kind = "surface", asset = "$id-$code", surface = "$code-pial", hemisphere = hemi,
        derivation = "project-to-surface",
    )
    field(id, measure, order; surface = false, extra...) = (
        id = id,
        estimand = "speech",
        measure = measure,
        selection = (level = "group",),
        domain = "grid-2mm",
        representations = surface ?
            [(kind = "volume", asset = id); [surface_rep(id, hemi, code) for (hemi, code) in HEMISPHERES]] :
            [(kind = "volume", asset = id)],
        order = order,
        extra...,
    )
    surface_domain(hemi, code) = (
        id = "ico3-$code",
        key = (
            descriptor = SURFACE_VERTICES_SCHEMA,
            size = surfaces[code].vertexCount,
            structuralFingerprint = surfaces[code].fingerprint,
        ),
        descriptor = (
            schema = SURFACE_VERTICES_SCHEMA,
            payload = (
                space = SURFACE_SPACE,
                hemisphere = hemi,
                vertexCount = surfaces[code].vertexCount,
                faceCount = surfaces[code].faceCount,
                topology = "$code-pial",
            ),
        ),
    )
    (
        core = "0.1",
        title = "Julia producer — synthetic speech group model",
        synopsis = "Written by modules/conformance/julia/producer.jl from documented ingredients only.",
        sensitivity = "group-level",
        axes = [(id = "level", label = "Level", values = ["group"])],
        domains = [(
            id = "grid-2mm",
            key = (
                descriptor = VOLUME_GRID_SCHEMA,
                size = prod(SHAPE),
                structuralFingerprint = fingerprint,
            ),
            descriptor = (
                schema = VOLUME_GRID_SCHEMA,
                payload = (
                    space = "MNI152NLin2009cAsym",
                    coordinateConvention = "RAS+",
                    spatialUnit = "mm",
                    ordinalLayout = "x-fastest",
                    shape = collect(SHAPE),
                    affine = affine_rows,
                ),
            ),
        ); [surface_domain(hemi, code) for (hemi, code) in HEMISPHERES]],
        assets = [
            # `x-julia-voxelStats` is an unknown field inside a known record: it
            # must survive admission, storage, and Scala/R round trips.
            asset("t1"; catalog = "synthetic:julia/t1", var"x-julia-voxelStats" = (min = 0.0, max = 100.0)),
            asset("speech-effect"),
            asset("speech-t"),
            asset("speech-z"),
            [asset("$code-pial") for (_, code) in HEMISPHERES]...,
            [asset("speech-$m-$code") for m in ("t", "z") for (_, code) in HEMISPHERES]...,
        ],
        analyses = [(
            id = "group-model",
            label = "Group model · speech (synthetic)",
            method = (
                schema = (
                    id = "org.example.julia/reducer/mean",
                    version = "0.1",
                    digest = "sha256:cc00000000000000000000000000000000000000000000000000000000000003",
                ),
                payload = (weights = "equal", inputs = 12),
            ),
            sampleSize = 12,
            estimands = [(id = "speech", label = "speech coefficient", order = 1)],
        )],
        resultFields = [
            field("speech-effect", "org.neuropublish.measure/effect", 1),
            field("speech-t", "org.neuropublish.measure/t-statistic", 2; surface = true,
                publishedDisplay = (
                    threshold = (mode = "two-sided", min = 2.5),
                    window = (min = -6.0, centre = 0.0, max = 6.0),
                    colormap = "cold-hot",
                )),
            field("speech-z", "org.neuropublish.measure/z-statistic", 3; surface = true,
                publishedDisplay = (
                    threshold = (mode = "two-sided", min = 2.3),
                    window = (min = -5.0, centre = 0.0, max = 5.0),
                    colormap = "cold-hot",
                )),
        ],
        underlays = [(asset = "t1", domain = "grid-2mm", label = "Synthetic T1")],
        surfaces = [(id = "$code-pial", asset = "$code-pial", domain = "ico3-$code", hemisphere = hemi,
                     kind = "pial", label = "Synthetic $hemi pial (icosphere)") for (hemi, code) in HEMISPHERES],
        provenance = (
            entities = [(id = "raw", label = "Synthetic raw data", hosted = false)],
            activities = [
                (id = "first-level-01", schema = RECEIPT_SCHEMA,
                    payload = (subject = "01", temporalNoise = "AR(2)", drift = "cosine-128s", hrf = "spmg1")),
                (id = "first-level-02", schema = RECEIPT_SCHEMA,
                    payload = (subject = "02", temporalNoise = "AR(1)", drift = "cosine-128s", hrf = "spmg1")),
                (id = "denoise", schema = (
                        id = "org.example.julia/denoise",
                        version = "0.1",
                        digest = "sha256:dd00000000000000000000000000000000000000000000000000000000000004",
                    ),
                    payload = (method = "wavelet", levels = 3, threshold = 0.05)),
                # the derivation receipt every surface representation names: how the producer put
                # its volume values onto the synthetic surfaces (an unknown record, retained as is)
                (id = "project-to-surface", schema = (
                        id = "org.example.julia/surface-projection",
                        version = "0.1",
                        digest = "sha256:ee00000000000000000000000000000000000000000000000000000000000005",
                    ),
                    payload = (method = "synthetic", note = "t = 6z/30 and z = 5.5(y+5)/25 at each vertex, clipped")),
            ],
            edges = [
                (from = "raw", to = "first-level-01"),
                (from = "raw", to = "first-level-02"),
                (from = "first-level-01", to = "denoise"),
                (from = "first-level-02", to = "denoise"),
                (from = "denoise", to = "speech-t"),
                (from = "speech-t", to = "project-to-surface"),
                (from = "project-to-surface", to = "speech-t-lh"),
                (from = "project-to-surface", to = "speech-t-rh"),
            ],
        ),
        # Unknown top-level field: the server and every round trip must preserve it.
        # No Julia version here: the bundle is deterministic, so `fixtures/julia` can be
        # regenerated on any Julia and compared byte for byte.
        var"x-julia-producer" = (
            version = "0.1",
            note = "unknown top-level extension; value preservation, not byte preservation",
            nested = (flags = [true, false], empty = Any[], ratio = 0.125),
        ),
    )
end

# ---------------------------------------------------------------------------
# HTTP (stdlib Downloads over libcurl; no HTTP.jl dependency)
# ---------------------------------------------------------------------------

# libcurl follows redirects by default; a redirected PUT would re-send the body (and any
# bearer) to a host the server never named, so redirect-following is off for every request.
const DOWNLOADER = Downloads.Downloader()
DOWNLOADER.easy_hook = (easy, info) ->
    Downloads.Curl.setopt(easy, Downloads.Curl.CURLOPT_FOLLOWLOCATION, 0)

# `scheme://host:port` of a URL, lower-cased, with the scheme's default port filled in; the
# bearer is sent only to URLs whose origin equals the --server origin.
function origin(url::AbstractString)
    m = match(r"^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)", url)
    m === nothing && return nothing
    scheme = lowercase(m.captures[1])
    authority = m.captures[2]
    occursin('@', authority) && (authority = split(authority, '@'; limit = 2)[2])
    hostport = if startswith(authority, '[')
        close = findfirst(']', authority)
        close === nothing && return nothing
        host = lowercase(authority[1:close])
        rest = authority[close+1:end]
        (host, startswith(rest, ':') ? rest[2:end] : "")
    else
        parts = split(authority, ':'; limit = 2)
        (lowercase(parts[1]), length(parts) == 2 ? parts[2] : "")
    end
    host, port = hostport
    isempty(port) && (port = scheme == "https" ? "443" : scheme == "http" ? "80" : "")
    "$scheme://$host:$port"
end

same_origin(url, server) = origin(url) !== nothing && origin(url) == origin(server)

function request(method, url, headers, body::Union{Nothing,Vector{UInt8}})
    out = IOBuffer()
    hs = [k => v for (k, v) in headers]
    r = Downloads.request(url;
        method = method,
        headers = hs,
        input = body === nothing ? nothing : IOBuffer(body),
        output = out,
        downloader = DOWNLOADER,
        throw = false)
    r isa Downloads.Response || fail("$method $url: $(r.message)")
    r.status, String(take!(out))
end

function expect(status_ok, method, url, headers, body)
    status, text = request(method, url, headers, body)
    status == status_ok || fail("$method $url returned HTTP $status: $(strip(text))")
    text
end

function publish(opts, bundle_dir, manifest_bytes, manifest_digest, assets)
    server = rstrip(opts["server"], '/')
    ws, proj = split(opts["project"], '/'; limit = 2)
    bearer = "Authorization" => "Bearer " * opts["token"]
    json = "Content-Type" => "application/json"
    parent = get(opts, "parent", nothing)
    inventory = [(digest = a.digest, size = a.size, mediaType = a.mediaType) for a in values(assets)]
    created = JSON3.read(expect(201, "POST", "$server/api/v1/workspaces/$ws/projects/$proj/upload-sessions",
        [bearer, json],
        Vector{UInt8}(JSON3.write((
            manifestDigest = manifest_digest,
            manifestSize = length(manifest_bytes),
            parent = parent,
            assets = inventory,
        )))))
    by_digest = Dict(a.digest => a for a in values(assets))
    for instr in created.missing
        a = get(by_digest, instr.digest, nothing)
        a === nothing && fail("server asked for $(instr.digest), which is not in the inventory")
        method = haskey(instr, :method) ? String(instr.method) : "PUT"
        headers = Pair{String,String}[]
        # the bearer only belongs on the control plane (same scheme, host, and port as
        # --server); a signed object-store URL carries its own authorization in `headers`
        same_origin(String(instr.url), server) && push!(headers, bearer)
        if haskey(instr, :headers)
            for (k, v) in pairs(instr.headers)
                push!(headers, String(k) => String(v))
            end
        end
        any(h -> lowercase(h.first) == "content-type", headers) ||
            push!(headers, "Content-Type" => "application/octet-stream")
        bytes = read(joinpath(bundle_dir, "assets", a.file))
        status, text = request(method, String(instr.url), headers, bytes)
        200 <= status < 300 || fail("$method $(instr.url) returned HTTP $status: $(strip(text))")
        println("uploaded ", a.file, " ", a.digest)
    end
    # the manifest target may be the control plane (bearer, 204) or a presigned object-store URL
    # (its own authorization in the query string; 200) — never send the bearer off the control plane
    let murl = String(created.manifestUrl)
        mheaders = Pair{String,String}["Content-Type" => "application/json"]
        same_origin(murl, server) && pushfirst!(mheaders, bearer)
        status, text = request("PUT", murl, mheaders, manifest_bytes)
        200 <= status < 300 || fail("PUT $murl returned HTTP $status: $(strip(text))")
    end
    commit = JSON3.read(expect(201, "POST", "$server/api/v1/upload-sessions/$(created.sessionId)/commit",
        [bearer, json], Vector{UInt8}(JSON3.write((message = get(opts, "message", "julia producer"),)))))
    String(commit.digest) == manifest_digest ||
        fail("server digest $(commit.digest) differs from ours $manifest_digest")
    println("revision ", commit.revisionId)
    println("server-digest ", commit.digest)
    println("viewUrl ", commit.viewUrl)
    # renditions are derived by the server (inline, or by a worker after commit): wait, bounded
    detail = nothing
    for attempt in 1:90
        detail = JSON3.read(expect(200, "GET", "$server/api/v1/revisions/$(commit.revisionId)", [bearer], nothing))
        ing = get(detail, :ingestion, nothing)
        ing !== nothing && String(get(ing, :status, "")) == "failed" &&
            fail("ingestion failed: $(get(ing, :error, ""))")
        all(r -> String(r.status) == "ready", detail.renditions) && break
        sleep(1.0)
    end
    for r in detail.renditions
        println("rendition ", r.assetId, " ", r.status)
    end
    all(r -> String(r.status) == "ready", detail.renditions) || fail("not every rendition is ready after 90 s")
end

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

function main()
    opts = parse_args(ARGS)
    out = opts["out"]
    mkpath(joinpath(out, "assets"))
    assets = Dict{String,Any}()
    probes = [(i = i, j = j, k = k, values = Dict{String,Float64}())
              for (i, j, k) in PROBE_VOXELS]
    for (id, kind) in (("t1", :t1), ("speech-effect", :effect), ("speech-t", :t), ("speech-z", :z))
        data = synthetic(kind)
        bytes = nifti_bytes(data)
        file = id * ".nii"
        write(joinpath(out, "assets", file), bytes)
        assets[id] = (file = file, digest = digest(bytes), size = length(bytes), mediaType = "application/x-nifti")
        for p in probes
            p.values[id] = Float64(data[p.i, p.j, p.k])
        end
    end
    # surfaces: one icosphere per hemisphere, and t/z vertex fields on each with known values
    verts, faces = icosphere(SURFACE_SUBDIVISIONS)
    surfaces = Dict{String,Any}()
    surface_oracle = Any[]
    field_oracle = Any[]
    for (hemi, code) in HEMISPHERES
        sign = hemi == "left" ? -1.0 : 1.0
        coords = Matrix{Float32}(undef, length(verts), 3)
        for (i, v) in enumerate(verts)
            coords[i, 1] = Float32(SURFACE_RADIUS * v[1] + sign * SURFACE_OFFSET)
            coords[i, 2] = Float32(SURFACE_RADIUS * v[2])
            coords[i, 3] = Float32(SURFACE_RADIUS * v[3])
        end
        sid = "$code-pial"
        bytes = gifti_surface(coords, faces, hemi)
        file = sid * ".surf.gii"
        write(joinpath(out, "assets", file), bytes)
        assets[sid] = (file = file, digest = digest(bytes), size = length(bytes), mediaType = GIFTI_MEDIA_TYPE)
        fingerprint = surface_vertices_fingerprint(
            SURFACE_VERTICES_SCHEMA.id, SURFACE_VERTICES_SCHEMA.version, SURFACE_SPACE, hemi, length(verts), faces)
        surfaces[code] = (vertexCount = length(verts), faceCount = length(faces), fingerprint = fingerprint)
        # probed vertices (0-based): first two, the middle one, the last
        probe_vertices = [0, 1, length(verts) ÷ 2, length(verts) - 1]
        vertex(i) = [Float64(coords[i + 1, 1]), Float64(coords[i + 1, 2]), Float64(coords[i + 1, 3])]
        push!(surface_oracle, (
            id = sid, hemisphere = hemi, vertexCount = length(verts), faceCount = length(faces),
            probeVertices = probe_vertices,
            coordinates = [vertex(i) for i in probe_vertices],
            face0 = collect(faces[1]), faceLast = collect(faces[end]),
            structuralFingerprint = fingerprint,
        ))
        for m in ("t", "z")
            values = Vector{Float32}(undef, length(verts))
            for i in 1:length(verts)
                y = Float64(coords[i, 2]); z = Float64(coords[i, 3])
                values[i] = m == "t" ? Float32(clamp(6.0 * z / 30.0, -6.0, 6.0)) :
                                       Float32(clamp(5.5 * (y + 5.0) / 25.0, -5.0, 5.0))
            end
            fid = "speech-$m-$code"
            fbytes = gifti_field(values, fid)
            ffile = fid * ".func.gii"
            write(joinpath(out, "assets", ffile), fbytes)
            assets[fid] = (file = ffile, digest = digest(fbytes), size = length(fbytes), mediaType = GIFTI_MEDIA_TYPE)
            total = 0.0                                    # sequential Float64 sum, not pairwise
            for v in values
                total += Float64(v)
            end
            push!(field_oracle, (
                id = fid, surface = sid, vertexCount = length(values),
                probeVertices = probe_vertices,
                values = [Float64(values[i + 1]) for i in probe_vertices],
                sum = total,
            ))
        end
    end
    # what an independent reader must see in the volumes (checked by nifti-check.R); outside
    # the manifest and outside assets/, so it is not part of the published bundle
    oracle = IOBuffer()
    JSON3.pretty(oracle, JSON3.write((
        shape = collect(SHAPE),
        spacing = collect(SPACING),
        origin = collect(ORIGIN),
        affine = [[affine()[r, c] for c in 1:4] for r in 1:4],
        files = [(id = id, file = assets[id].file) for id in sort(collect(keys(assets))) if endswith(assets[id].file, ".nii")],
        probes = [(voxel1 = [p.i, p.j, p.k],
                   values = [(id = id, value = p.values[id]) for id in sort(collect(keys(p.values)))])
                  for p in probes],
        surfaces = surface_oracle,
        fields = field_oracle,
    )))
    write(joinpath(out, "oracle.json"), String(take!(oracle)) * "\n")
    # pretty-printed for readers; the digest covers exactly these bytes
    pretty = IOBuffer()
    JSON3.pretty(pretty, JSON3.write(manifest(assets, surfaces)))
    text = String(take!(pretty))
    manifest_bytes = Vector{UInt8}(codeunits(text * "\n"))
    write(joinpath(out, "manifest.json"), manifest_bytes)
    d = digest(manifest_bytes)
    write(joinpath(out, "manifest.sha256"), d[8:end] * "\n")
    println("digest ", d)
    haskey(opts, "server") && publish(opts, out, manifest_bytes, d, assets)
end

main()
