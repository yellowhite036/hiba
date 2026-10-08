import cv2
import json
import numpy as np
import os
import time
import sys


def trainDeepModel(node, useHog, useRatio, inputFolderPath, treePath, output_path, model_name):
    HOGParams = {
        "_winSize": (64, 64),
        "_blockSize": (16, 16),
        "_blockStride": (8, 8),
        "_cellSize": (8, 8),
        "_nbins": 9
    }
    HOGDescriptor = cv2.HOGDescriptor(**HOGParams)

    output_folder = None
    descriptors_list = {}

    def addTrainSet(src, useHog, useRatio, x, y, w, h, ratio, video_width=960, video_height=540):
        img = cv2.resize(src, (64, 64))
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        # 是否使用HOG
        if useHog:
            descriptors = HOGDescriptor.compute(gray)
            descriptors = descriptors.ravel()
        else:
            norm_gray = np.zeros((64, 64), dtype=np.float32)
            norm_gray = cv2.normalize(gray, norm_gray, 0, 1, cv2.NORM_MINMAX, dtype=cv2.CV_8UC1)
            descriptors = norm_gray.ravel()
        # 是否使用比例參數
        if useRatio:
            x_parameter = x / video_width
            y_parameter = y / video_height
            w_parameter = w / video_width
            h_parameter = h / video_height
            ratio_params = [x_parameter, y_parameter, w_parameter, h_parameter, ratio]
            descriptors = np.append(ratio_params, [descriptors])
        return descriptors

    # ---------- train ----------
    def trainSubModel(x, y, outputName):
        st = time.time()
        print("========================================")
        x = np.array(x, dtype=np.float32)
        y = np.array(y, dtype=np.int32)
        output_model_path = output_folder + "/" + outputName + ".xml"
        svm = cv2.ml.SVM_create()
        svm.setType(cv2.ml.SVM_C_SVC)  # Set SVM type
        svm.setKernel(cv2.ml.SVM_LINEAR)  # Set SVM Kernel
        svm.setC(0.1)  # Set parameter C
        svm.setGamma(1.0)  # Set parameter Gamma
        svm.trainAuto(x, cv2.ml.ROW_SAMPLE, y)
        svm.save(output_model_path)
        print(" -          x :", x.shape)
        print(" -          y :", y.shape)
        print(" -          C :", svm.getC())
        print(" -      Gamma :", svm.getGamma())
        print(" - using time :", (time.time() - st))
        print(" --------------", outputName, "train done!")
        print("========================================\n")

    def findCategory(data, parentLabel, maxSubModel):
        train_x = []
        train_y = []
        checkList = []

        for child in data["children"]:
            label = child["name"]
            sub_category_flag = True if len(data["children"]) > 1 else False
            if len(child["children"]) > 1:
                buffer_x = findCategory(child, parentLabel + "-" + label, maxSubModel)
                train_x += buffer_x
                train_y += [label] * len(buffer_x)
            elif label in descriptors_list.keys():
                train_x += descriptors_list[label]
                train_y += [label] * len(descriptors_list[label])

        if label == maxSubModel:
            trainSubModel(train_x, train_y, parentLabel)
        elif maxSubModel == "":
            trainSubModel(train_x, train_y, parentLabel)
        return train_x

    def findAllCategoryInTree(data):
        category = []
        for child in data["children"]:
            if len(child["children"]) == 0:
                for key in child["value"]:
                    category.append(key)
            else:
                sub_category = findAllCategoryInTree(child)
                category.extend(sub_category)
        return category

    try:
        total_time = time.time()
        # input path
        category_array = os.listdir(inputFolderPath)

        # output path
        output_folder = output_path
        # load deep training setting
        with open(treePath, 'r') as f:
            deep_layer = json.load(f)
        all_category = np.unique(findAllCategoryInTree(deep_layer))
        print("all_category: ", all_category)

        # 開始訓練
        descriptors_list = {}
        maxSubModel = "" # 這部份先隨便寫, 用於判斷訓練的model是不是最上層的root用
        for fineName in category_array:           
            if os.path.isdir(inputFolderPath + os.sep + fineName):
                # 訓練目標為root.xml, 其路徑會直接給整個目錄
                checkList = []
                for subFile in os.listdir(inputFolderPath + os.sep + fineName):
                    category = subFile.split(".")[0]
                    if category not in all_category:
                        continue
                    npz_path = inputFolderPath + os.sep + fineName + os.sep + subFile
                    myArch = np.load(npz_path)
                    index = 0
                    count = 0
                    category_count = len(myArch)
                    print("Sample zip file: ", npz_path)
                    for index in range(0, category_count):
                        count += 1
                        try:
                            descriptors_list[category].append(myArch[str(index)])
                        except KeyError:
                            descriptors_list[category] = [myArch[str(index)]]
                for child in deep_layer["children"]:
                    label = child["name"]
                    checkList.append(int(label))
                maxSubModel = str(max(checkList)) # 取得編號最大的子模型編號
            else:
                category = fineName.split(".")[0]
                if category not in all_category:
                    continue

                npz_path = inputFolderPath + fineName
                myArch = np.load(npz_path)
                index = 0
                count = 0
                category_count = len(myArch)
                print("Sample zip file: ", npz_path)
                for index in range(0, category_count):
                    count += 1
                    try:
                        descriptors_list[category].append(myArch[str(index)])
                    except KeyError:
                        descriptors_list[category] = [myArch[str(index)]]
            
            print(f'\r{category} {count}/{category_count} ', end='')
        
        os.makedirs(output_folder, exist_ok=True)
        findCategory(deep_layer, model_name, maxSubModel)
        print("total time:", (time.time() - total_time))
        return [True, ""]
    except Exception as e:
        print(e)
        return [False, str(e)]
    
if __name__ == "__main__":
    # simple_path    = "./npzTest/3/"
    # tree_path      = "./testTree.json"
    # out_model_path = "./model/"
    # out_model_name = "root-1"
    simple_path    = sys.argv[1];
    tree_path      = sys.argv[2];
    out_model_path = sys.argv[3];
    out_model_name = sys.argv[4];
    print(trainDeepModel(" ", True, True, simple_path, tree_path, out_model_path, out_model_name))
