import cv2
import numpy as np
import os
import sys

class SVM:
    __slots__ = ["video_width", "video_height", "use_ratio", "use_hog", "model_path", "hog_params", "hog", "model"]
    def __init__(self, videoSize = (960, 540)):
        self.video_width, self.video_height = videoSize
        self.model = None
        self.use_hog = True
        self.use_ratio = None
        self.model_path = None
        self.hog_params = {
            "_winSize"     : (64, 64),
            "_blockSize"   : (16, 16),
            "_blockStride" : (8, 8),
            "_cellSize"    : (8, 8),
            "_nbins"       : 9
        }
        self.hog = cv2.HOGDescriptor(**self.hog_params)

    def getDescriptors(self, sampleDir, outputPath, image_size = (64, 64)):

        files = os.listdir(sampleDir)
        count = 0
        hogDict = {}
        try:
            for img in files:
                if img.split(".")[-1] != "png":
                    continue
                x, y, w, h, ratio = img.split("-")[1].split(".")[0].split("_")
                img = cv2.imread(sampleDir + img)
                img = cv2.resize(img, image_size)
                # 是否使用HOG
                if self.use_hog:
                    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                    descriptors = self.hog.compute(gray).ravel()
                else:
                    norm_img = np.zeros(image_size, dtype=np.float32)
                    norm_img = cv2.normalize(img, norm_img, 0, 1, cv2.NORM_MINMAX, dtype=cv2.CV_8UC1)
                    descriptors = norm_img.ravel()
                # 是否使用位置參數
                if self.use_ratio:
                    ratio = w / h
                    x_parameter = x / self.video_width
                    y_parameter = y / self.video_height
                    w_parameter = w / self.video_width
                    h_parameter = h / self.video_height
                    image_params = [x_parameter, y_parameter, w_parameter, h_parameter, ratio]
                    descriptors = np.append(image_params, [descriptors])
                hogDict[str(count)] = descriptors
                count += 1
            np.savez(outputPath, **hogDict)
            return True
        except Exception as e:
            print(e)
            return False

def loadnpz():
    myArch = np.load('./112.npz')
    print(myArch['0'])
    print(myArch['1'])

if __name__ == '__main__':
    # sampleDir = "D:/00-NKFUST/01-GSLab/HierarchicalBrokeringArchitecture/N04-TestReport/20230410-3wTestModel,Sample,Tree/441k901/441_901/112/"
    # model_Path = "./112.npz"
    # print(SVM().getDescriptors(sampleDir, model_Path))
    # loadnpz()
    print(SVM().getDescriptors(sys.argv[1], sys.argv[2]))