import os
from PIL import Image, ImageDraw

src_path = r"C:\Users\yaswa\Downloads\app_icon.jpeg"
res_base = r"c:\Users\yaswa\Downloads\gemini-live\app\src\main\res"

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

def create_icons():
    img = Image.open(src_path).convert("RGBA")
    
    # Crop outer white space to let the green circular border fill the icon perfectly
    width, height = img.size
    left = int(width * 0.06)
    top = int(height * 0.06)
    right = int(width * 0.94)
    bottom = int(height * 0.94)
    img = img.crop((left, top, right, bottom))
    
    adaptive_sizes = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432
    }
    
    # Generate a regular drawable app logo for Jetpack Compose (since it cannot load adaptive XMLs directly)
    logo_dir = os.path.join(res_base, "drawable")
    os.makedirs(logo_dir, exist_ok=True)
    resample_filter = Image.Resampling.LANCZOS if hasattr(Image, "Resampling") else Image.ANTIALIAS
    img_logo = img.resize((192, 192), resample_filter)
    img_logo.save(os.path.join(logo_dir, "app_logo.png"), "PNG")
    
    for folder, size in sizes.items():
        dest_dir = os.path.join(res_base, folder)
        os.makedirs(dest_dir, exist_ok=True)
        
        # Use Image.Resampling.LANCZOS or Image.ANTIALIAS
        resample_filter = Image.Resampling.LANCZOS if hasattr(Image, "Resampling") else Image.ANTIALIAS
        
        # 1. Legacy Launcher Icon (full square)
        normal_img = img.resize((size, size), resample_filter)
        normal_img.save(os.path.join(dest_dir, "ic_launcher.png"), "PNG")
        
        # 2. Legacy Launcher Round Icon (clipped circular)
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)
        
        round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_img.paste(normal_img, (0, 0), mask=mask)
        round_img.save(os.path.join(dest_dir, "ic_launcher_round.png"), "PNG")
        
        # 3. Modern Adaptive Background Layer (solid white or matching color)
        asize = adaptive_sizes[folder]
        bg_layer = Image.new("RGBA", (asize, asize), (255, 255, 255, 255))
        bg_layer.save(os.path.join(dest_dir, "ic_launcher_background.png"), "PNG")
        
        # 4. Modern Adaptive Foreground Layer (green circle scaled to fill circular mask)
        # Scaled to 74% of the canvas size to slightly bleed over the circular mask
        fg_layer = Image.new("RGBA", (asize, asize), (0, 0, 0, 0))
        fg_size = int(asize * 0.74)
        fg_resized = img.resize((fg_size, fg_size), resample_filter)
        offset = (asize - fg_size) // 2
        fg_layer.paste(fg_resized, (offset, offset), mask=fg_resized)
        fg_layer.save(os.path.join(dest_dir, "ic_launcher_foreground.png"), "PNG")
        
        print(f"Generated adaptive and legacy icons for {folder} ({size}x{size} & {asize}x{asize})")

if __name__ == "__main__":
    create_icons()
