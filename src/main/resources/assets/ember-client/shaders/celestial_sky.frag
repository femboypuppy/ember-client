#version 330 core
in vec3 v_Direction;
layout (std140) uniform SkyData { vec4 u_AccentStrength; vec4 u_Motion; vec4 u_Horizon; };
out vec4 color;
float hash(vec3 p) { p = fract(p * 0.3183099 + vec3(.1,.2,.3)); p *= 17.; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float noise(vec3 p) {
    vec3 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
    return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
        mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float fbm(vec3 p) { float n=0., a=.5; for(int i=0;i<4;i++){ n+=a*noise(p); p=p*2.03+vec3(3.1,1.7,2.4); a*=.5; } return n; }
void main() {
    vec3 d=normalize(v_Direction), accent=u_AccentStrength.rgb;
    float t=u_Motion.x*.025, style=u_Motion.y;
    float height=clamp(d.y,0.,1.);
    vec3 base=mix(accent*.27+vec3(.018),accent*.035+vec3(.008),pow(height,.5));
    float cloud=fbm(d*4. + vec3(t*.15,0.,t*.08));
    float band=exp(-pow((d.y-.3-.18*sin(d.x*2.8+d.z*1.6)),2.)*16.);
    if(style<.5) {
        float nebula=smoothstep(.25,.75,cloud)*band;
        base+=mix(accent,vec3(.72),.14)*nebula*.43;
        base+=accent*pow(max(0.,1.-abs(cloud-.5)*4.),3.)*band*.055;
    } else if(style<1.5) {
        float curtains=sin(d.x*11.+sin(d.z*6.+t)*2.+cloud*5.+t);
        float veil=pow(max(0.,curtains),3.)*band*(.35+cloud*.65);
        base+=mix(accent,vec3(.8),.16)*veil*.5;
    } else {
        float sunset=exp(-pow(d.y-.08,2.)*18.)*(.55+.45*sin(d.x*2.+d.z+t*.12));
        base+=mix(accent,vec3(.65),.12)*sunset*.17;
        base+=accent*cloud*.035;
    }
    // Spherical noise avoids UV seams and singularities at the zenith.
    // Intensity changes the sky's brightness, never its coverage. A translucent
    // replacement would leave the original blue daytime sky mixed into every pixel.
    vec3 sky=base*(.35+.65*u_AccentStrength.a)*u_Motion.z;
    float horizon=exp(-pow((d.y-.035)/max(.015,u_Horizon.y),2.))*smoothstep(-.12,.015,d.y);
    // The band lives in the opaque sky pass, so mountains and translucent edges occlude it.
    sky+=mix(accent,vec3(.82),.12)*horizon*u_Horizon.x*u_Horizon.z*(.75+.25*u_Motion.z);
    color=vec4(sky,1.);
}
