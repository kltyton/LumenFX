package com.kltyton.lumenfx.client.compat.iris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kltyton.lumenfx.client.compat.iris.IrisCompositeStagePlanner.PassInput;
import com.kltyton.lumenfx.client.compat.iris.IrisCompositeStagePlanner.StagePlan;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IrisCompositeStagePlannerTest {
    @Test
    void splitsBeforeAndAfterDofWhenDofWritesColortex0WithEncodingTransition() {
        List<PassInput> passes = List.of(
                new PassInput("composite", "void main() { colortex0 = texture(colortex0, texcoord); }", List.of(), true, false),
                new PassInput("composite1", """
                        void main() {
                            vec3 color = pow(texture(colortex0, texcoord).rgb, vec3(2.2));
                            color = DepthOfField(color, texcoord);
                            gl_FragData[0] = vec4(color, 1.0);
                        }
                        """, List.of(), true, true),
                new PassInput("composite2", "void main() { gl_FragData[0] = texture(colortex0, texcoord); }", List.of(), true, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.GAMMA_2_2);
        assertTrue(plan.hasDofPass());
        assertFalse(plan.hasMotionBlurPass());
        assertTrue(plan.hasAnyDeferredStage());
        assertTrue(plan.hasDeferredAfterDof());
        assertFalse(plan.hasSeparateAfterMotionBlur());
        assertEquals(2, plan.afterDofPassIndex());
        assertFalse(plan.afterDofAltSide());
        assertEquals(ColourEncoding.LINEAR, plan.afterDofEncoding());
        assertEquals(EnumSet.of(TranslucencyPass.BEFORE_DOF), plan.immediateStages());
        assertEquals(EnumSet.of(TranslucencyPass.AFTER_DOF, TranslucencyPass.AFTER_MOTION_BLUR), plan.afterDofStages());
        assertTrue(plan.afterMotionBlurStages().isEmpty());
    }

    @Test
    void defersAfterDofToEndOfCompositeLoopUsingAltAtEndFlag() {
        List<PassInput> passes = List.of(
                new PassInput("composite", "void main() { gl_FragData[0] = texture(colortex0, texcoord); }", List.of(), true, false),
                new PassInput("composite1", "void main() { gl_FragData[0] = dofBlur(texcoord); }", List.of(), true, true)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDeferredAfterDof());
        assertEquals(2, plan.afterDofPassIndex());
        assertTrue(plan.afterDofAltSide());
        assertEquals(ColourEncoding.LINEAR, plan.afterDofEncoding());
        assertEquals(EnumSet.of(TranslucencyPass.BEFORE_DOF), plan.immediateStages());
        assertEquals(EnumSet.of(TranslucencyPass.AFTER_DOF, TranslucencyPass.AFTER_MOTION_BLUR), plan.afterDofStages());
    }

    @Test
    void separatesAfterMotionBlurWhenMotionBlurRunsAfterDof() {
        List<PassInput> passes = List.of(
                new PassInput("composite", "void main() { vec3 c = calculateDOF(texcoord); colortex0 = vec4(c, 1.0); }", List.of(), true, false),
                new PassInput("composite1", "void main() { vec3 c = DoMotionBlur(texcoord); colortex0 = vec4(c, 1.0); }", List.of(), true, true),
                new PassInput("composite2", "void main() { vec3 c = pow(colortex0.rgb, vec3(1.0 / 2.2)); }", List.of(), true, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertTrue(plan.hasMotionBlurPass());
        assertTrue(plan.hasSeparateAfterMotionBlur());
        assertEquals(1, plan.afterDofPassIndex());
        assertTrue(plan.afterDofAltSide());
        assertEquals(2, plan.afterMotionBlurPassIndex());
        assertFalse(plan.afterMotionBlurAltSide());
        assertEquals(EnumSet.of(TranslucencyPass.BEFORE_DOF), plan.immediateStages());
        assertEquals(EnumSet.of(TranslucencyPass.AFTER_DOF), plan.afterDofStages());
        assertEquals(EnumSet.of(TranslucencyPass.AFTER_MOTION_BLUR), plan.afterMotionBlurStages());
    }

    @Test
    void fallsBackToImmediateWhenDofOnlyAppearsInCommentsOrPassDoesNotWriteColortex0() {
        List<PassInput> passes = List.of(
                new PassInput("composite", """
                        // DepthOfField(color, texcoord);
                        /* DoDOF(texcoord); DOF_STRENGTH = 1.0; */
                        void main() { gl_FragData[0] = texture(colortex0, texcoord); }
                        """, List.of(), true, false),
                new PassInput("composite1", "void main() { gl_FragData[1] = DoDepthOfField(texcoord); }", List.of(), false, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, false, ColourEncoding.GAMMA_2_2);
        assertFalse(plan.hasDofPass());
        assertFalse(plan.hasMotionBlurPass());
        assertFalse(plan.hasAnyDeferredStage());
        assertEquals(EnumSet.allOf(TranslucencyPass.class), plan.immediateStages());
        assertTrue(plan.afterDofStages().isEmpty());
        assertTrue(plan.afterMotionBlurStages().isEmpty());
    }

    @Test
    void detectsComputeDofPassWritingColorImg0() {
        List<PassInput> passes = List.of(
                new PassInput("composite_compute", null,
                        List.of("layout(rgba16f) uniform image2D colorimg0;\nvoid main() { imageStore(colorimg0, gid, DoF(gid)); }"),
                        false, true),
                new PassInput("composite1", "void main() { colortex0 = vec4(1.0); }", List.of(), true, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertTrue(plan.hasDeferredAfterDof());
        assertEquals(1, plan.afterDofPassIndex());
        assertFalse(plan.afterDofAltSide());
    }

    @Test
    void detectsComplementaryAutoFocusDofAndSkipsScreenSpaceBlocklight() {
        List<PassInput> passes = List.of(
                new PassInput("composite2",
                        "/* DRAWBUFFERS:0 */\nvoid main() {\n  vec3 color = texture2DLod(colortex0, texCoord, 0).rgb;\n  vec3 blocklight = vec3(0.1);\n  colortex0 = vec4(color + blocklight, 1.0);\n}",
                        List.of(), true, false),
                new PassInput("composite3",
                        "/* DRAWBUFFERS:0 */\nvec2 dofOffsets[4];\nvec2 getPolygonOffset(int i) { return dofOffsets[i]; }\nvec3 DoWorldBlur(vec3 color, float z0) {\n  float cocView = clamp(abs(z0 - 0.5), 0.0, 1.0);\n  vec2 offset = getPolygonOffset(0);\n  return color * cocView + offset.x;\n}\nvoid main() {\n  vec3 color = texture2DLod(colortex0, texCoord, 0).rgb;\n  colortex0 = vec4(DoWorldBlur(color, 0.25), 1.0);\n}",
                        List.of(), true, true),
                new PassInput("composite4",
                        "/* DRAWBUFFERS:3 */\nvoid main() { gl_FragData[0] = vec4(0.1); }",
                        List.of(), false, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, false, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertTrue(plan.hasDeferredAfterDof());
        assertEquals(2, plan.afterDofPassIndex());
        assertFalse(plan.afterDofAltSide());
        assertFalse(plan.hasSeparateAfterMotionBlur());
    }

    @Test
    void detectsComplementaryPreprocessedMotionBlurAfterDofAndPreservesLinearEncoding() {
        List<PassInput> passes = List.of(
                new PassInput("composite2",
                        "/* DRAWBUFFERS:0 */\nvoid main() { colortex0 = texture2DLod(colortex0, texCoord, 0); }",
                        List.of(), true, false),
                new PassInput("composite3",
                        "/* DRAWBUFFERS:0 */\nvec2 dofOffsets[4];\nvec2 getPolygonOffset(int i) { return dofOffsets[i]; }\nvec3 DoWorldBlur(vec3 color, float z0) {\n  float cocView = abs(z0 - 0.5);\n  return color * getPolygonOffset(0).x + cocView;\n}\nvoid main() { colortex0 = vec4(DoWorldBlur(texture2DLod(colortex0, texCoord, 0).rgb, 0.25), 1.0); }",
                        List.of(), true, true),
                new PassInput("composite4",
                        "/* DRAWBUFFERS:30 */\nvoid main() {\n  vec4 currentPosition = vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);\n  vec4 previousPosition = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(currentPosition.xyz, 1.0));\n  previousPosition /= previousPosition.w;\n  vec2 velocity = (currentPosition.xy - previousPosition.xy) * 0.5;\n  vec2 coord = texCoord;\n  vec3 color = vec3(0.0);\n  for (int i = 0; i < 4; i++) {\n    color += texture2DLod(colortex0, coord, 0).rgb;\n    coord += velocity;\n  }\n  gl_FragData[1] = vec4(color * 0.25, 1.0);\n}",
                        List.of(), true, false),
                new PassInput("composite5",
                        "/* DRAWBUFFERS:35 */\nvoid main() { gl_FragData[0] = vec4(0.0); }",
                        List.of(), false, true)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertTrue(plan.hasMotionBlurPass());
        assertTrue(plan.hasDeferredAfterDof());
        assertEquals(2, plan.afterDofPassIndex());
        assertFalse(plan.afterDofAltSide());
        assertTrue(plan.hasSeparateAfterMotionBlur());
        assertTrue(plan.hasDeferredAfterMotionBlur());
        assertEquals(3, plan.afterMotionBlurPassIndex());
        assertTrue(plan.afterMotionBlurAltSide());
        assertEquals(ColourEncoding.LINEAR, plan.afterMotionBlurEncoding());
    }

    @Test
    void skipsDisabledWorldBlurPassthroughPass() {
        List<PassInput> passes = List.of(
                new PassInput("composite3",
                        "/* DRAWBUFFERS:0 */\nvoid main() {\n  vec3 color = texture2DLod(colortex0, texCoord, 0).rgb;\n  colortex0 = vec4(color, 1.0);\n}",
                        List.of(), true, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertFalse(plan.hasDofPass());
        assertFalse(plan.hasDeferredAfterDof());
        assertFalse(plan.hasDeferredAfterMotionBlur());
    }

    @Test
    void infersGenericPreCompositeEncodingWithoutFalsePositiveOnHelpersOrMaterialReads() {
        assertEquals(ColourEncoding.GAMMA_2_2, IrisCompositeStagePlanner.inferPreCompositeEncoding(List.of(
                "vec3 srgbToLinear(vec3 c) { return pow(c, vec3(2.2)); }\nvoid main() {\n  color = pow(color, vec3(2.2));\n  color = DoCompTonemap(color);\n}"
        ), true));

        assertEquals(ColourEncoding.SRGB, IrisCompositeStagePlanner.inferPreCompositeEncoding(List.of(
                "void main() {\n  color = srgbToLinear(color);\n  color = tonemapACES(color);\n}"
        ), false));

        assertEquals(ColourEncoding.LINEAR, IrisCompositeStagePlanner.inferPreCompositeEncoding(List.of(
                "vec3 gammaToLinear(vec3 c) { return pow(c, vec3(2.2)); }\nvoid main() {\n  vec3 albedoSample = pow(texture2D(colortex1, texCoord).rgb, vec3(2.2));\n  color = tonemapACES(color + albedoSample * 0.0);\n  color = linearToSrgb(color);\n}"
        ), true));

        assertEquals(ColourEncoding.LINEAR, IrisCompositeStagePlanner.inferPreCompositeEncoding(List.of(
                "void main() { gl_FragData[0] = texture2D(colortex0, texCoord); }"
        ), true));

        assertEquals(ColourEncoding.SRGB, IrisCompositeStagePlanner.inferPreCompositeEncoding(List.of(
                "void main() { gl_FragData[0] = texture2D(colortex0, texCoord); }"
        ), false));
    }

    @Test
    void tracksSrgbAndComputeOnlyColorImg0EncodingTransitions() {
        List<PassInput> passes = List.of(
                new PassInput("composite", "void main() { color = DepthOfField(color, texcoord); colortex0 = vec4(color, 1.0); }",
                        List.of(), true, false),
                new PassInput("composite1_compute", null, List.of("""
                        void main() {
                            vec3 c = imageLoad(colorimg0, ivec2(gl_GlobalInvocationID.xy)).rgb;
                            c = linearToSrgb(c);
                            imageStore(colorimg0, ivec2(gl_GlobalInvocationID.xy), vec4(c, 1.0));
                        }
                        """), false, true),
                new PassInput("composite2", "void main() { color = DoMotionBlur(color); color = srgbToLinear(color); colortex0 = vec4(color, 1.0); }",
                        List.of(), true, false)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertEquals(1, plan.afterDofPassIndex());
        assertEquals(ColourEncoding.LINEAR, plan.afterDofEncoding());
        assertTrue(plan.hasMotionBlurPass());
        assertEquals(3, plan.afterMotionBlurPassIndex());
        assertEquals(ColourEncoding.LINEAR, plan.afterMotionBlurEncoding());

        List<PassInput> srgbAfterMotionPasses = List.of(
                new PassInput("composite", "void main() { color = DepthOfField(color, texcoord); colortex0 = vec4(color, 1.0); }",
                        List.of(), true, false),
                new PassInput("composite1_compute", null, List.of("""
                        void main() {
                            vec3 c = imageLoad(colorimg0, ivec2(gl_GlobalInvocationID.xy)).rgb;
                            c = linearToSrgb(c);
                            imageStore(colorimg0, ivec2(gl_GlobalInvocationID.xy), vec4(c, 1.0));
                        }
                        """), false, true)
        );
        StagePlan srgbPlan = IrisCompositeStagePlanner.plan(srgbAfterMotionPasses, false, ColourEncoding.LINEAR);
        assertEquals(ColourEncoding.LINEAR, srgbPlan.afterDofEncoding());
        assertEquals(EnumSet.of(TranslucencyPass.AFTER_DOF, TranslucencyPass.AFTER_MOTION_BLUR), srgbPlan.afterDofStages());

        List<PassInput> computeDofToSrgb = List.of(
                new PassInput("composite_compute_dof", null, List.of("""
                        void main() {
                            vec3 c = imageLoad(colorimg0, ivec2(gl_GlobalInvocationID.xy)).rgb;
                            c = DepthOfField(c, texcoord);
                            c = linearToSrgb(c);
                            imageStore(colorimg0, ivec2(gl_GlobalInvocationID.xy), vec4(c, 1.0));
                        }
                        """), false, false),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texcoord); }",
                        List.of(), true, true)
        );
        StagePlan computePlan = IrisCompositeStagePlanner.plan(computeDofToSrgb, true, ColourEncoding.LINEAR);
        assertTrue(computePlan.hasDofPass());
        assertEquals(1, computePlan.afterDofPassIndex());
        assertEquals(ColourEncoding.SRGB, computePlan.afterDofEncoding());
    }

    @Test
    void ignoresUncalledDofHelperAndDetectsIntermediateBufferDofStage() {
        List<PassInput> passes = List.of(
                new PassInput("composite_unused_decl", """
                        vec3 DoWorldBlur(vec3 color) {
                            return bokehBlur(color);
                        }
                        void main() {
                            colortex0 = texture(colortex0, texCoord);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_dof_to_colortex4", """
                        void main() {
                            vec3 blurred = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            colortex4 = vec4(blurred, 1.0);
                        }
                        """, List.of(), false, false, 1 << 4),
                new PassInput("composite_resolve_colortex4", """
                        void main() {
                            colortex0 = texture(colortex4, texCoord);
                        }
                        """, List.of(), true, true, 1)
        );

        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertEquals(3, plan.afterDofPassIndex());

        List<PassInput> onlyUnusedHelper = List.of(passes.get(0));
        StagePlan unusedPlan = IrisCompositeStagePlanner.plan(onlyUnusedHelper, false, ColourEncoding.LINEAR);
        assertFalse(unusedPlan.hasDofPass());
        assertFalse(unusedPlan.hasDeferredAfterDof());
    }

    @Test
    void detectsGenericJitterContractsWithoutFabricatingZeroForUnknownTaa() {
        var halton8 = IrisShaderProfile.detectGenericJitterFromSources(List.of("""
                uniform float framemod8;
                vec2 jitterOffsets8[8] = vec2[8](
                    vec2( 0.125,-0.375),
                    vec2(-0.125, 0.375),
                    vec2( 0.625, 0.125),
                    vec2( 0.375,-0.625),
                    vec2(-0.625, 0.625),
                    vec2(-0.875,-0.125),
                    vec2( 0.375,-0.875),
                    vec2( 0.875, 0.875)
                );
                vec2 TAAJitter(vec2 coord, float w) {
                    vec2 offset = jitterOffsets8[int(framemod8)] * (w / vec2(viewWidth, viewHeight));
                    return coord + offset;
                }
                void main() {
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position.xy = TAAJitter(gl_Position.xy, gl_Position.w);
                }
                """));
        assertTrue(halton8.known());
        assertEquals(8, halton8.sampleCount());
        assertEquals(1.0F, halton8.scale(), 1e-6F);

        var twoSample = IrisShaderProfile.detectGenericJitterFromSources(List.of("""
                uniform float framemod2;
                vec2 jitterOffsets2[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                void main() {
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position.xy += jitterOffsets2[int(framemod2)] * (gl_Position.w / vec2(viewWidth, viewHeight));
                }
                """));
        assertTrue(twoSample.known());
        assertEquals(2, twoSample.sampleCount());
        assertEquals(1.0F, twoSample.scale(), 1e-6F);

        var noTaa = IrisShaderProfile.detectGenericJitterFromSources(List.of("""
                void main() {
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                }
                """));
        assertTrue(noTaa.known());
        assertEquals(1, noTaa.sampleCount());
        assertEquals(0.0F, noTaa.scale(), 1e-6F);

        var unknownTaa = IrisShaderProfile.detectGenericJitterFromSources(List.of("""
                uniform vec2 customHaltonOffset;
                vec2 customJitter(vec2 c) { return c + customHaltonOffset; }
                void main() {
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position.xy = customJitter(gl_Position.xy);
                }
                """));
        assertFalse(unknownTaa.known());
        assertEquals(0, unknownTaa.sampleCount());
        assertTrue(Float.isNaN(unknownTaa.scale()));

        var unorderedScattered = IrisShaderProfile.detectGenericJitterFromSources(List.of("""
                uniform float framemod8;
                void main() {
                    vec2 a = vec2(0.125,-0.375) + vec2(-0.125,0.375) + vec2(0.625,0.125) + vec2(0.375,-0.625);
                    vec2 b = vec2(-0.625,0.625) + vec2(-0.875,-0.125) + vec2(0.375,-0.875) + vec2(0.875,0.875);
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position.xy += (a + b) * 0.001 * framemod8;
                }
                """));
        assertFalse(unorderedScattered.known());
        assertEquals(0, unorderedScattered.sampleCount());

        var customOrder = IrisShaderProfile.detectGenericJitterFromSources(List.of("""
                uniform float framemod8;
                const vec2 customHalton[8] = vec2[8](
                    vec2( 0.875, 0.875),
                    vec2(-0.125, 0.375),
                    vec2( 0.625, 0.125),
                    vec2( 0.375,-0.625),
                    vec2(-0.625, 0.625),
                    vec2(-0.875,-0.125),
                    vec2( 0.375,-0.875),
                    vec2( 0.125,-0.375)
                );
                void main() {
                    gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                    gl_Position.xy += customHalton[int(framemod8)] * (gl_Position.w / vec2(viewWidth, viewHeight));
                }
                """));
        assertTrue(customOrder.known());
        assertEquals(8, customOrder.sampleCount());
        assertEquals(0.875F, customOrder.offsets()[0][0], 1e-6F);
        assertEquals(-0.375F, customOrder.offsets()[7][1], 1e-6F);
    }

    @Test
    void respectsHelperExecutionOrderForPreCompositeAndNestedEncodingTransitions() {
        List<PassInput> passes = List.of(
                new PassInput("composite_dof", """
                        vec3 readScene() {
                            return srgbToLinear(texture(colortex0, texCoord).rgb);
                        }
                        void main() {
                            vec3 c = DepthOfField(readScene(), texCoord);
                            colortex0 = vec4(linearToSrgb(readScene()) + c * 0.0, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", """
                        void main() {
                            colortex0 = texture(colortex0, texCoord);
                        }
                        """, List.of(), true, true, 1)
        );
        StagePlan plan = IrisCompositeStagePlanner.plan(passes, true, ColourEncoding.LINEAR);
        assertTrue(plan.hasDofPass());
        assertEquals(ColourEncoding.SRGB, plan.afterDofEncoding());

        ColourEncoding preComp = IrisCompositeStagePlanner.inferPreCompositeEncoding(List.of("""
                vec3 readScene() {
                    return srgbToLinear(texture(colortex0, texCoord).rgb);
                }
                void main() {
                    colortex0 = vec4(ACESFilm(readScene()), 1.0);
                }
                """), true);
        assertEquals(ColourEncoding.SRGB, preComp);

        List<PassInput> singleExprNested = List.of(
                new PassInput("composite_dof_nested", """
                        void main() {
                            vec3 c = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            colortex0 = vec4(linearToSrgb(srgbToLinear(c)), 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        StagePlan nestedPlan = IrisCompositeStagePlanner.plan(singleExprNested, true, ColourEncoding.LINEAR);
        assertEquals(ColourEncoding.SRGB, nestedPlan.afterDofEncoding());

        List<PassInput> argBeforeCallee = List.of(
                new PassInput("composite_dof_call_order", """
                        vec3 encodeOut(vec3 x) {
                            return linearToSrgb(x);
                        }
                        vec3 decodeIn(vec3 c) {
                            return srgbToLinear(c);
                        }
                        void main() {
                            vec3 c = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            colortex0 = vec4(encodeOut(decodeIn(c)), 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        StagePlan orderPlan = IrisCompositeStagePlanner.plan(argBeforeCallee, true, ColourEncoding.LINEAR);
        assertEquals(ColourEncoding.SRGB, orderPlan.afterDofEncoding());
    }

    @Test
    void reordersGenericJitterTablesByFrameIndexAndRejectsOpaqueIndexing() {
        String rotatedTwoFrame = """
                uniform int framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.0, 1.0), vec2(1.0, 0.0));
                void main() {
                    vec2 offset = taaOffsets[(framemod2 + 1) % 2];
                    gl_Position = ftransform();
                    gl_Position.xy += offset;
                }
                """;
        var rotated = IrisShaderProfile.detectGenericJitterFromSources(List.of(rotatedTwoFrame));
        assertTrue(rotated.known());
        assertEquals(2, rotated.sampleCount());
        assertEquals(1.0F, rotated.offsets()[0][0], 1e-6F);
        assertEquals(0.0F, rotated.offsets()[0][1], 1e-6F);
        assertEquals(0.0F, rotated.offsets()[1][0], 1e-6F);
        assertEquals(1.0F, rotated.offsets()[1][1], 1e-6F);

        String opaqueIndexed = """
                uniform int framemod2;
                uniform int frameShift;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.0), vec2(-0.25, 0.0));
                void main() {
                    vec2 offset = taaOffsets[(framemod2 + frameShift) % 2];
                    gl_Position = ftransform();
                    gl_Position.xy += offset;
                }
                """;
        var opaque = IrisShaderProfile.detectGenericJitterFromSources(List.of(opaqueIndexed));
        assertTrue(!opaque.known());

        // #11: Per-axis jitter scale vec2(2, 1) and explicit UNKNOWN_JITTER on unresolvable multiplier.
        String perAxisScale = """
                uniform float framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                void main() {
                    vec2 offset = taaOffsets[int(framemod2)];
                    gl_Position = ftransform();
                    gl_Position.xy += vec2(2, 1) * offset * gl_Position.w / vec2(viewWidth, viewHeight);
                }
                """;
        var perAxis = IrisShaderProfile.detectGenericJitterFromSources(List.of(perAxisScale));
        assertTrue(perAxis.known());
        assertEquals(2.0F, perAxis.scaleX(), 1e-6F);
        assertEquals(1.0F, perAxis.scaleY(), 1e-6F);

        String dynamicScale = """
                uniform float framemod2;
                uniform float userJitterMul;
                const vec2 taaOffsets[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                void main() {
                    vec2 offset = taaOffsets[int(framemod2)];
                    gl_Position = ftransform();
                    gl_Position.xy += userJitterMul * offset * gl_Position.w / vec2(viewWidth, viewHeight);
                }
                """;
        var dyn = IrisShaderProfile.detectGenericJitterFromSources(List.of(dynamicScale));
        assertTrue(!dyn.known());

        // #10: gl_Position.xy -= offset AND consecutive += accumulation must sign/sum properly!
        String subJitter = """
                uniform float framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                void main() {
                    vec2 offset = taaOffsets[int(framemod2)];
                    gl_Position = ftransform();
                    gl_Position.xy -= offset * gl_Position.w / vec2(viewWidth, viewHeight);
                }
                """;
        var subRes = IrisShaderProfile.detectGenericJitterFromSources(List.of(subJitter));
        assertTrue(subRes.known());
        assertEquals(-1.0F, subRes.scaleX(), 1e-6F);
        assertEquals(-1.0F, subRes.scaleY(), 1e-6F);

        String multiAccumJitter = """
                uniform float framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                void main() {
                    vec2 offset = taaOffsets[int(framemod2)];
                    gl_Position = ftransform();
                    gl_Position.xy += offset * 0.5 * gl_Position.w / vec2(viewWidth, viewHeight);
                    gl_Position.xy += offset * 0.25 * gl_Position.w / vec2(viewWidth, viewHeight);
                }
                """;
        var accumRes = IrisShaderProfile.detectGenericJitterFromSources(List.of(multiAccumJitter));
        assertTrue(accumRes.known());
        assertEquals(0.75F, accumRes.scaleX(), 1e-6F);
        assertEquals(0.75F, accumRes.scaleY(), 1e-6F);

        String unappliedOffset = """
                uniform float framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                vec2 opaqueJitter(vec2 o) { return o * 0.5; }
                void main() {
                    vec2 offset = taaOffsets[int(framemod2)];
                    gl_Position = ftransform();
                    gl_Position.xy = opaqueJitter(gl_Position.xy);
                }
                """;
        // Notice: offset was never applied via recognized op on gl_Position -> MUST NOT fabricate known=true!
        assertTrue(!IrisShaderProfile.detectGenericJitterFromSources(List.of(unappliedOffset)).known());
        String conditionalJitter = """
                uniform float framemod2;
                uniform bool useJitter;
                const vec2 taaOffsets[2] = vec2[2](vec2(1.0, 0.0), vec2(0.0, 1.0));
                void main() {
                    vec2 offset = taaOffsets[int(framemod2)];
                    gl_Position = ftransform();
                    if (useJitter) {
                        gl_Position.xy += offset * gl_Position.w / vec2(viewWidth, viewHeight);
                    }
                }
                """;
        assertTrue(!IrisShaderProfile.detectGenericJitterFromSources(List.of(conditionalJitter)).known());
    }

    @Test
    void tracksColortex0MrtAttachmentEncodingWithoutPollutionFromAuxiliaryOutputs() {
        List<PassInput> mrtPasses = List.of(
                new PassInput("composite_mrt", """
                        /* DRAWBUFFERS:01 */
                        layout(location = 0) out vec4 fragColor;
                        layout(location = 1) out vec4 auxOut;
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec3 encodedScene = linearToSrgb(scene);
                            vec3 decodedAux = srgbToLinear(texture(colortex1, texCoord).rgb);
                            fragColor = vec4(encodedScene, 1.0);
                            auxOut = vec4(decodedAux, 1.0);
                        }
                        """, List.of(), true, false, 3),
                new PassInput("composite_after", "void main() { gl_FragData[0] = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        StagePlan mrtPlan = IrisCompositeStagePlanner.plan(mrtPasses, true, ColourEncoding.LINEAR);
        assertEquals(ColourEncoding.SRGB, mrtPlan.afterDofEncoding());

        List<PassInput> swappedSlots = List.of(
                new PassInput("composite_mrt_swap", """
                        /* DRAWBUFFERS:10 */
                        layout(location = 0) out vec4 auxOut;
                        layout(location = 1) out vec4 fragColor;
                        void main() {
                            DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            auxOut = vec4(srgbToLinear(texture(colortex1, texCoord).rgb), 1.0);
                            fragColor = vec4(linearToSrgb(texture(colortex0, texCoord).rgb), 1.0);
                        }
                        """, List.of(), true, false, 3, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1, 0)
        );
        StagePlan swapPlan = IrisCompositeStagePlanner.plan(swappedSlots, true, ColourEncoding.LINEAR);
        assertEquals(ColourEncoding.SRGB, swapPlan.afterDofEncoding());

        // #9: Formal parameter binding + return value dataflow through pass(c).
        List<PassInput> paramFlow = List.of(
                new PassInput("composite_param_pass", """
                        vec3 pass(vec3 v) { return v; }
                        void main() {
                            DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec3 c = linearToSrgb(texture(colortex0, texCoord).rgb);
                            fragColor = vec4(pass(c), 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(paramFlow, true, ColourEncoding.LINEAR).afterDofEncoding());

        // #11: Alpha write `.a` after RGB write MUST NOT overwrite RGB encoding; vec4(rgb, alphaCall) also keeps RGB!
        List<PassInput> alphaSeparate = List.of(
                new PassInput("composite_alpha_write", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(linearToSrgb(scene), 1.0);
                            fragColor.a = srgbToLinear(texture(colortex1, texCoord).rgb).r;
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(alphaSeparate, true, ColourEncoding.LINEAR).afterDofEncoding());

        List<PassInput> inlineAlphaVec4 = List.of(
                new PassInput("composite_inline_alpha", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(linearToSrgb(scene), srgbToLinear(texture(colortex1, texCoord).rgb).r);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(inlineAlphaVec4, true, ColourEncoding.LINEAR).afterDofEncoding());
    }

    @Test
    void resolvesOverloadedFunctionsTracksComponentDemandAndCapturesSwizzledJitterMatrix() {
        // #8: Overload resolution: helper(vec3) has srgbToLinear, helper(vec4) has linearToSrgb.
        // Calling helper(vec4Src) MUST select only the vec4 overload and produce SRGB (not LINEAR)!
        List<PassInput> overloadPasses = List.of(
                new PassInput("composite_overload", """
                        vec3 applyCurve(vec3 c) { return srgbToLinear(c); }
                        vec4 applyCurve(vec4 c) { return vec4(linearToSrgb(c.rgb), c.a); }
                        void main() {
                            DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec4 scene = texture(colortex0, texCoord);
                            fragColor = applyCurve(scene);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(overloadPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "GLSL overload resolution must select vec4 overload without expanding unrelated vec3 overload");

        // #9a: Alpha packed inside intermediate `vec4 packed` MUST NOT pollute RGB encoding when only RGB flows to fragColor!
        List<PassInput> packedPasses = List.of(
                new PassInput("composite_packed_alpha", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec4 packed = vec4(linearToSrgb(scene), srgbToLinear(texture(colortex1, texCoord).rgb).r);
                            fragColor = packed;
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(packedPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Unused alpha channel in intermediate vec4 packed must not overwrite RGB encoding");

        // #9b: Alpha component ACTUALLY read by RGB (x.a -> fragColor.rgb) MUST propagate encoding to RGB!
        List<PassInput> alphaToRgbPasses = List.of(
                new PassInput("composite_alpha_to_rgb", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec4 packed = vec4(1.0, 1.0, 1.0, linearToSrgb(scene).r);
                            fragColor = vec4(packed.a, packed.a, packed.a, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(alphaToRgbPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Alpha channel read into fragColor.rgb via x.a must propagate encoding");

        // #10: Swapped jitter swizzle `offset.yx * vec2(0.5, 0.25)` must produce off-diagonal matrix [0, 0.5, 0.25, 0], not diagonal!
        String swappedVertex = """
                uniform int framemod8;
                const vec2 taaOffsets[8] = vec2[8](
                    vec2(0.125, -0.375), vec2(-0.125, 0.375), vec2(0.625, 0.125), vec2(0.375, -0.625),
                    vec2(-0.625, 0.625), vec2(-0.875, -0.125), vec2(0.375, -0.875), vec2(0.875, 0.875)
                );
                void main() {
                    gl_Position = ftransform();
                    vec2 offset = taaOffsets[framemod8];
                    gl_Position.xy += offset.yx * vec2(0.5, 0.25) * gl_Position.w;
                }
                """;
        var jitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(swappedVertex));
        assertTrue(jitter.known());
        assertEquals(0.0F, jitter.matrix()[0], 1e-5F);
        assertEquals(0.5F, jitter.matrix()[1], 1e-5F);
        assertEquals(0.25F, jitter.matrix()[2], 1e-5F);
        assertEquals(0.0F, jitter.matrix()[3], 1e-5F);

        // #7: Builtin sin(scene) type inference disambiguates vec3 vs float overload; ambiguous call fails closed to UNKNOWN
        List<PassInput> builtinOverloadPasses = List.of(
                new PassInput("composite_builtin_overload", """
                        vec3 convertValue(vec3 v) { return linearToSrgb(v); }
                        float convertValue(float v) { return srgbToLinear(vec3(v)).r; }
                        void main() {
                            DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec3 scene = texture(colortex0, texCoord).rgb;
                            fragColor = vec4(convertValue(sin(scene)), 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(builtinOverloadPasses, true, ColourEncoding.LINEAR).afterDofEncoding());

        List<PassInput> ambiguousOverloadPasses = List.of(
                new PassInput("composite_ambiguous_overload", """
                        vec3 convertValue(vec3 v) { return linearToSrgb(v); }
                        float convertValue(float v) { return srgbToLinear(vec3(v)).r; }
                        void main() {
                            DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(convertValue(unknownExternalInput), 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.UNKNOWN,
                IrisCompositeStagePlanner.plan(ambiguousOverloadPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Ambiguous overload involving encoding transformations must fail closed to UNKNOWN");

        // #8: Type-aware constructor channel mapping preserves scalar `float alpha` in `vec4(rgb, alpha)`
        List<PassInput> scalarAlphaPasses = List.of(
                new PassInput("composite_scalar_alpha", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec3 rgb = linearToSrgb(scene);
                            float alpha = srgbToLinear(texture(colortex1, texCoord).rgb).r;
                            vec4 packed = vec4(rgb, alpha);
                            fragColor = packed;
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(scalarAlphaPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Scalar float alpha variable in vec4(rgb, alpha) must stay isolated to alpha channel");

        // #9: Affine 2x3 jitter tracks constant centering offset (-0.5) and rejects unknown runtime bias
        String affineVertex = """
                uniform int framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 offset = taaOffsets[framemod2];
                    offset -= vec2(0.5);
                    gl_Position.xy += offset * gl_Position.w;
                }
                """;
        var affineJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(affineVertex));
        assertTrue(affineJitter.known());
        assertEquals(1.0F, affineJitter.affine()[0], 1e-5F);
        assertEquals(0.0F, affineJitter.affine()[1], 1e-5F);
        assertEquals(-0.5F, affineJitter.affine()[2], 1e-5F);
        assertEquals(0.0F, affineJitter.affine()[3], 1e-5F);
        assertEquals(1.0F, affineJitter.affine()[4], 1e-5F);
        assertEquals(-0.5F, affineJitter.affine()[5], 1e-5F);

        String unknownBiasVertex = """
                uniform int framemod2;
                uniform vec2 customBias;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 offset = taaOffsets[framemod2];
                    offset -= customBias;
                    gl_Position.xy += offset * gl_Position.w;
                }
                """;
        var unknownBiasJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(unknownBiasVertex));
        assertFalse(unknownBiasJitter.known(), "Unknown non-constant bias subtracted from offset must not fabricate affine jitter");

        // #7: Lexical block scoping keeps inner `vec3 c` shadowed without hijacking outer `c`
        List<PassInput> blockScopePasses = List.of(
                new PassInput("composite_block_scope", """
                        void main() {
                            DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec3 c = linearToSrgb(texture(colortex0, texCoord).rgb);
                            {
                                vec3 c = srgbToLinear(texture(colortex1, texCoord).rgb);
                            }
                            fragColor = vec4(c, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(blockScopePasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Inner block shadowing `vec3 c` must not hijack outer `c` flowing into fragColor");

        // #8: Recursive vec4(vec2, vec2) maps B channel only to ba.x and drops ba.y alpha decode
        List<PassInput> vec2PairPasses = List.of(
                new PassInput("composite_vec2_pair", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            vec2 rg = linearToSrgb(scene).rg;
                            vec2 ba = vec2(linearToSrgb(scene).b, srgbToLinear(texture(colortex1, texCoord).rgb).r);
                            fragColor = vec4(rg, ba);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(vec2PairPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "vec4(rg, ba) must demand only ba.x for RGB and drop ba.y alpha decode");

        // #9: Partial/full non-jitter gl_Position overwrites clear stale jitter on overwritten components
        String fullOverwriteVertex = """
                uniform int framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 baseXY = gl_Position.xy;
                    gl_Position.xy += taaOffsets[framemod2] * gl_Position.w;
                    gl_Position.xy = baseXY;
                }
                """;
        var fullClearedJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(fullOverwriteVertex));
        assertTrue(fullClearedJitter.known());
        assertEquals(0.0F, fullClearedJitter.affine()[0], 1e-5F);
        assertEquals(0.0F, fullClearedJitter.affine()[4], 1e-5F);

        String xOnlyOverwriteVertex = """
                uniform int framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 baseXY = gl_Position.xy;
                    gl_Position.xy += taaOffsets[framemod2] * gl_Position.w;
                    gl_Position.x = baseXY.x;
                }
                """;
        var xClearedJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(xOnlyOverwriteVertex));
        assertTrue(xClearedJitter.known());
        assertEquals(0.0F, xClearedJitter.affine()[0], 1e-5F, "Overwriting gl_Position.x with baseXY.x must clear X jitter row");
        assertEquals(1.0F, xClearedJitter.affine()[4], 1e-5F, "Overwriting gl_Position.x must leave Y jitter row intact");

        String postTransformUnknownVertex = """
                uniform int framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    gl_Position.xy += taaOffsets[framemod2] * gl_Position.w;
                    vec2 unknown = sin(gl_Position.xy);
                    gl_Position.xy = unknown;
                }
                """;
        var postUnknownJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(postTransformUnknownVertex));
        assertFalse(postUnknownJitter.known(), "Opaque transform reading jittered gl_Position must fail closed as UNKNOWN");

        // #5 (re-audit): LHS swizzle maps demand per-component so fragColor.rgba drops alpha decode and fragColor.ag only reads .g as green
        List<PassInput> rgbaSwizzlePasses = List.of(
                new PassInput("composite_lhs_rgba", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            float aux = srgbToLinear(texture(colortex1, texCoord).rgb).r;
                            fragColor.rgba = vec4(linearToSrgb(scene), aux);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(rgbaSwizzlePasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "fragColor.rgba must drop alpha srgbToLinear and classify RGB encoding as SRGB");

        List<PassInput> agSwizzlePasses = List.of(
                new PassInput("composite_lhs_ag", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            float aux = srgbToLinear(texture(colortex1, texCoord).rgb).r;
                            fragColor = vec4(linearToSrgb(scene), 1.0);
                            fragColor.ag = vec2(aux, linearToSrgb(scene).g);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(agSwizzlePasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "fragColor.ag = vec2(alphaDecode, rgbEncode) must ignore vec2.x (A channel) and see only rgbEncode on G");

        // #6 (re-audit): Jitter extraction respects lexical block shadowing for initialized and uninitialized locals
        String shadowedBlockJitterVertex = """
                uniform int framemod2;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 offset = taaOffsets[framemod2];
                    {
                        vec2 offset = taaOffsets[framemod2] * 2.0;
                    }
                    {
                        vec2 offset;
                        offset = taaOffsets[framemod2] * 3.0;
                    }
                    gl_Position.xy += offset * gl_Position.w;
                }
                """;
        var shadowedJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(shadowedBlockJitterVertex));
        assertTrue(shadowedJitter.known(), "Block-scoped shadowed jitter local must keep outer offset intact");
        assertEquals(1.0F, shadowedJitter.affine()[0], 1e-5F);
        assertEquals(1.0F, shadowedJitter.affine()[4], 1e-5F);

        // #3 (new re-audit): Dead overwritten fragColor RGB encode is eliminated while self-reads and conditional overrides stay live
        List<PassInput> overwrittenSrgbPasses = List.of(
                new PassInput("composite_overwritten_rgb", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(linearToSrgb(scene), 1.0);
                            fragColor = vec4(scene, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.LINEAR,
                IrisCompositeStagePlanner.plan(overwrittenSrgbPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Unconditional fragColor overwrite must drop dead earlier linearToSrgb write");

        List<PassInput> selfReadPasses = List.of(
                new PassInput("composite_self_read", """
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(linearToSrgb(scene), 1.0);
                            fragColor = vec4(fragColor.rgb * 0.5, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(selfReadPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Self-read fragColor.rgb must keep prior live linearToSrgb write");

        List<PassInput> conditionalOverwritePasses = List.of(
                new PassInput("composite_cond_overwrite", """
                        uniform float cond;
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(linearToSrgb(scene), 1.0);
                            if (cond > 0.5) {
                                fragColor = vec4(scene, 1.0);
                            }
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.SRGB,
                IrisCompositeStagePlanner.plan(conditionalOverwritePasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Conditional fragColor overwrite must NOT kill earlier unconditional linearToSrgb fallback");

        List<PassInput> conditionalDeadBeforeUnconditionalPasses = List.of(
                new PassInput("composite_cond_before_uncond", """
                        uniform float cond;
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            if (cond > 0.5) {
                                fragColor = vec4(linearToSrgb(scene), 1.0);
                            }
                            fragColor = vec4(scene, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.LINEAR,
                IrisCompositeStagePlanner.plan(conditionalDeadBeforeUnconditionalPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Conditional encode overwritten unconditionally afterwards must still be dropped");

        // #4 (new re-audit): Partial component overwrite (offset.x = 0.0) preserves offset.y jitter and fails closed on unknown RHS
        String partialZeroJitterVertex = """
                uniform int framemod2;
                uniform float viewWidth;
                uniform float viewHeight;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 offset = taaOffsets[framemod2];
                    offset.x = 0.0;
                    gl_Position.xy += offset * (gl_Position.w / vec2(viewWidth, viewHeight));
                    gl_Position.xy += taaOffsets[framemod2] * (gl_Position.w / vec2(viewWidth, viewHeight));
                }
                """;
        var partialZeroJitter = IrisShaderProfile.detectGenericJitterFromSources(List.of(partialZeroJitterVertex));
        assertTrue(partialZeroJitter.known(), "offset.x = 0.0 must preserve offset.y jitter scale");
        assertEquals(1.0F, partialZeroJitter.affine()[0], 1e-5F);
        assertEquals(2.0F, partialZeroJitter.affine()[4], 1e-5F);

        String partialUnknownJitterVertex = """
                uniform int framemod2;
                uniform float dynamicX;
                const vec2 taaOffsets[2] = vec2[2](vec2(0.25, 0.75), vec2(0.75, 0.25));
                void main() {
                    gl_Position = ftransform();
                    vec2 offset = taaOffsets[framemod2];
                    offset.x = dynamicX;
                    gl_Position.xy += offset * gl_Position.w;
                }
                """;
        assertFalse(IrisShaderProfile.detectGenericJitterFromSources(List.of(partialUnknownJitterVertex)).known(),
                "Unknown RHS on partial component of jitter variable must fail closed as UNKNOWN");

        // #22: ColourEncoding.UNKNOWN distinct from LINEAR, branch divergence -> UNKNOWN, gaux1 alias DoF propagation
        assertTrue(ColourEncoding.LINEAR.shaderCode() != ColourEncoding.UNKNOWN.shaderCode(),
                "UNKNOWN must not share shaderCode 0 with LINEAR");
        assertEquals(ColourEncoding.SRGB, ColourEncoding.UNKNOWN.orDefaultForFormat(false));
        assertEquals(ColourEncoding.LINEAR, ColourEncoding.UNKNOWN.orDefaultForFormat(true));

        List<PassInput> ifElseBranchPasses = List.of(
                new PassInput("composite_branch", """
                        uniform float flag;
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            if (flag > 0.5) {
                                fragColor = vec4(linearToSrgb(scene), 1.0);
                            } else {
                                fragColor = vec4(scene, 1.0);
                            }
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.UNKNOWN,
                IrisCompositeStagePlanner.plan(ifElseBranchPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Divergent if/else branch encoding must fail closed as UNKNOWN");

        List<PassInput> ternaryBranchPasses = List.of(
                new PassInput("composite_ternary", """
                        uniform float flag;
                        void main() {
                            vec3 scene = DepthOfField(texture(colortex0, texCoord).rgb, texCoord);
                            fragColor = vec4(flag > 0.5 ? linearToSrgb(scene) : scene, 1.0);
                        }
                        """, List.of(), true, false, 1),
                new PassInput("composite_after", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(ColourEncoding.UNKNOWN,
                IrisCompositeStagePlanner.plan(ternaryBranchPasses, true, ColourEncoding.LINEAR).afterDofEncoding(),
                "Divergent ternary branch encoding must fail closed as UNKNOWN");

        List<PassInput> gauxAliasDofPasses = List.of(
                new PassInput("composite0", "/* DRAWBUFFERS:4 */\nvoid main() { colortex4 = DepthOfField(colortex0); }",
                        List.of(), false, false, 1 << 4),
                new PassInput("composite1", "void main() { colortex0 = texture(gaux1, texCoord); }",
                        List.of(), true, false, 1),
                new PassInput("composite2", "void main() { colortex0 = texture(colortex0, texCoord); }",
                        List.of(), true, true, 1)
        );
        assertEquals(2, IrisCompositeStagePlanner.plan(gauxAliasDofPasses, true, ColourEncoding.LINEAR).afterDofPassIndex(),
                "gaux1 read of DoF-tainted colortex4 must advance afterDofPassIndex past composite1");
    }

    @Test
    void depthTextureCopyHandlesIrisDepthWithoutCopySrcAcrossOpenGlVulkanAndCallSites() throws Exception {
        int copySrc = com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC;
        int copyDst = com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST;
        int sampled = com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING;
        int renderAttachment = com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT;
        int irisDepth1Usage = copyDst | sampled; // Iris 1.11.4 RenderTargets.getDepthTextureNoTranslucents() usage = 5 (no USAGE_COPY_SRC)
        int fullDepthUsage = copySrc | copyDst | sampled | renderAttachment;

        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.NO_OP,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, irisDepth1Usage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, irisDepth1Usage, true, true));

        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.DIRECT_COPY,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true, false));

        // Iris depth1 lacks USAGE_COPY_SRC: must NEVER choose DIRECT_COPY (which throws IllegalArgumentException in Blaze3D).
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.GL_BLIT,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, irisDepth1Usage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true, false));
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.SHADER_PASS,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, irisDepth1Usage, false,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, false, false));

        // External depth target lacking USAGE_COPY_DST must also bypass DIRECT_COPY.
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.GL_BLIT,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, sampled | renderAttachment, true, false));
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.SHADER_PASS,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, false,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, sampled | renderAttachment, false, false));

        // Cross-format depth copies cannot use raw copies or GL depth blits.
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.SHADER_PASS,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT_S8_UINT, 854, 480, fullDepthUsage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true, false));
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.SHADER_PASS,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT_S8_UINT, 854, 480, fullDepthUsage, true, false));
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.SHADER_THEN_GL_BLIT,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, fullDepthUsage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT_S8_UINT, 854, 480, irisDepth1Usage, true, false));
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.GL_BLIT,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 1920, 1080, fullDepthUsage, true,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 960, 540, fullDepthUsage, true, false));
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.SHADER_PASS,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D24_UNORM_S8_UINT, 1920, 1080, fullDepthUsage, false,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 960, 540, fullDepthUsage, false, false));

        // Non-GL depth copy without copy flags and without sampling/attachment fails explicitly instead of crashing later.
        assertEquals(com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.Mode.UNSUPPORTED,
                com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy.selectMode(
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, copyDst, false,
                        com.mojang.blaze3d.GpuFormat.D32_FLOAT, 854, 480, copyDst, false, false));

        // Verify the depth conversion shader asset is present with the expected GLSL interface.
        try (var stream = IrisCompositeStagePlannerTest.class.getResourceAsStream("/assets/lumenfx/shaders/post/depth/copy_depth.fsh")) {
            assertTrue(stream != null, "copy_depth.fsh must exist in runtime shader resources");
            String shader = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(shader.contains("#version 330") && shader.contains("SourceDepth") && shader.contains("gl_FragDepth"));
        }

        // Bytecode guard: native composition and external depth copies share the capability-aware copy path.
        String[][] guardedMethods = {
                { "com/kltyton/lumenfx/client/render/hdr/WorldHdrCapture.class", "beginComposition" },
                { "com/kltyton/lumenfx/client/render/hdr/WorldHdrCapture.class", "resumeNativeTransparency" },
                { "com/kltyton/lumenfx/client/render/hdr/WorldHdrCapture.class", "finishComposition" },
                { "com/kltyton/lumenfx/client/render/hdr/WorldHdrCapture.class", "copyTranslucencyDepth" },
                { "com/kltyton/lumenfx/client/render/hdr/WorldHdrCapture.class", "copyDepth" },
                { "com/kltyton/lumenfx/client/render/mesh/nanite/NaniteVisibilityTarget.class", "rasterize" },
                { "com/kltyton/lumenfx/client/render/temporal/geometry/ParticleMotionDepth.class", "attachment" },
                { "com/kltyton/lumenfx/client/render/temporal/geometry/ParticleMotionDepth.class", "snapshotPreColorDepth" },
                { "com/kltyton/lumenfx/client/render/EffectGpuRenderer.class", "snapshotOpaqueMotionDepth" },
                { "com/kltyton/lumenfx/client/render/scene/surface/adaptive/AdaptiveParticleBuffer.class", "copyDepth" }
        };
        for (String[] entry : guardedMethods) {
            try (var in = IrisCompositeStagePlannerTest.class.getClassLoader().getResourceAsStream(entry[0])) {
                assertTrue(in != null, "Missing class bytecode for " + entry[0]);
                var reader = new org.objectweb.asm.ClassReader(in.readAllBytes());
                boolean[] foundMethod = { false };
                boolean[] directCopyCall = { false };
                reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        if (!name.equals(entry[1])) return null;
                        foundMethod[0] = true;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                                if ("copyTextureToTexture".equals(mName) || "copyDepthFrom".equals(mName)) {
                                    directCopyCall[0] = true;
                                }
                            }
                        };
                    }
                }, 0);
                assertTrue(foundMethod[0], "Method " + entry[1] + " not found in " + entry[0]);
                assertFalse(directCopyCall[0], entry[0] + "#" + entry[1] + " must route through DepthTextureCopy");
            }
        }
    }

    @Test
    void stagesExternalPhotonOpaqueDepthToOwnedRenderAttachment() throws Exception {
        int irisDepth1Usage = com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST
                | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING;
        int fullDepthUsage = irisDepth1Usage
                | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC
                | com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT;

        // Iris depth1 (usage=5) lacks USAGE_RENDER_ATTACHMENT and must be staged before Photon opens a raster pass.
        assertTrue(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.requiresPhotonOpaqueDepthCopy(irisDepth1Usage, false));
        assertTrue(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.requiresPhotonOpaqueDepthCopy(irisDepth1Usage, true));
        // Shared host shaderpack depth is also staged even if attachment-capable so deferred Photon depth writes stay isolated.
        assertTrue(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.requiresPhotonOpaqueDepthCopy(fullDepthUsage, true));
        // Owned attachment-capable depth needs no redundant copy.
        assertFalse(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.requiresPhotonOpaqueDepthCopy(fullDepthUsage, false));

        // Bytecode guard: drawExternalPhoton must stage opaque depth via ensurePhotonExternalOpaqueDepth, and release() must close it.
        try (var in = IrisCompositeStagePlannerTest.class.getClassLoader()
                .getResourceAsStream("com/kltyton/lumenfx/client/render/hdr/WorldHdrCapture.class")) {
            assertTrue(in != null);
            var reader = new org.objectweb.asm.ClassReader(in.readAllBytes());
            boolean[] callsStageHelper = { false };
            boolean[] callsCopyDepth = { false };
            boolean[] releaseClosesDepth = { false };
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    if ("drawExternalPhoton".equals(name)) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                                if ("ensurePhotonExternalOpaqueDepth".equals(mName)) callsStageHelper[0] = true;
                            }
                        };
                    }
                    if ("ensurePhotonExternalOpaqueDepth".equals(name)) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                                if ("copyDepth".equals(mName)) callsCopyDepth[0] = true;
                            }
                        };
                    }
                    if ("release".equals(name)) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                                if ("photonExternalOpaqueDepth".equals(fName)) releaseClosesDepth[0] = true;
                            }
                        };
                    }
                    return null;
                }
            }, 0);
            assertTrue(callsStageHelper[0], "WorldHdrCapture#drawExternalPhoton must stage external opaque depth");
            assertTrue(callsCopyDepth[0], "WorldHdrCapture#ensurePhotonExternalOpaqueDepth must copy via capability-aware copyDepth");
            assertTrue(releaseClosesDepth[0], "WorldHdrCapture#release must close photonExternalOpaqueDepth");
        }
    }
}
