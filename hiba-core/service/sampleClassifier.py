import json
import os
import sys

def classifier(inputFolderPath, treePath):
    sample_array = os.listdir(inputFolderPath)

    with open(treePath, 'r') as f:
        deepLayer = json.load(f)

    for child in deepLayer["children"]:
        trainPath = inputFolderPath + "/" + child["name"] + "/"
        os.makedirs(trainPath, exist_ok = True)
        childrenTree = '_'.join(child["value"])
        print(childrenTree)
        for category in sample_array:
            if os.path.isfile(inputFolderPath + "/" + category):
                if "_" in category:
                    value = category.split("-")[1].split(".")[0]
                    if childrenTree == value:
                        fileType = category.split(".")[1]
                        if fileType == "npz" and "_" in category:
                            os.rename(inputFolderPath + "/" + category, trainPath + category.split("-")[0] + ".npz")
                        elif fileType == "npz" and "_" not in category:
                            os.rename(inputFolderPath + "/" + category, trainPath + category)
                        elif fileType == "xml":
                            os.rename(inputFolderPath + "/" + category, inputFolderPath + "/" + "root-" + child["name"] + ".xml")
                else:
                    if childrenTree in category:
                        fileType = category.split(".")[1]
                        if fileType == "npz" and "_" in category:
                            os.rename(inputFolderPath + "/" + category, trainPath + category.split("-")[0] + ".npz")
                        elif fileType == "npz" and "_" not in category:
                            os.rename(inputFolderPath + "/" + category, trainPath + category)
                        elif fileType == "xml":
                            os.rename(inputFolderPath + "/" + category, inputFolderPath + "/" + "root-" + child["name"] + ".xml")
    return True

def getFolder(directory):
    fileArray = os.listdir(directory)
    fileSize = {}
    for dir in fileArray:
        if os.path.isdir(directory + "/" + dir):
            childrenFolder = directory + "/" + dir
            size = 0
            for file in os.listdir(childrenFolder):
                size += os.path.getsize(childrenFolder + "/" + file)
            fileSize[dir] = size
    fileSize = dict(sorted(fileSize.items(), key = lambda item : item[1]))
    fileSort = list(fileSize.keys())
    print("由小到大排序(含大小): ", fileSize)
    print("由小到大排序: ",fileSort)

    return fileSort

if __name__ == "__main__":
    args = sys.argv[1:]
    inputFolderPath = args[0]
    treePath        = args[1]
    print(classifier(inputFolderPath, treePath))
    