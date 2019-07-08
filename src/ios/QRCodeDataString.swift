//
//  QRCodeDataString.swift
//  Imagic Camera
//
//  Created by Ivan Burdeyny on 6/12/19.
//

import Foundation

struct QRCodeDataString: Codable {
    let coords: [CGPoint]
    let text: String?
    let relation: Double
    let inside: Int
}

extension Bool {
    var int: Int {
        return self ? 1 : 0
    }
}

extension CGPoint {

    func middle(_ point: CGPoint) -> CGPoint {
        return CGPoint(x: (x + point.x) / 2, y: (y + point.y) / 2)
    }

    func distance(_ point: CGPoint) -> CGFloat {
        return sqrt( (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y) )
    }
}

extension CGRect {
    func contains(_ points: [CGPoint]) -> Bool {
        for point in points {
            if !self.contains(point) {
                return false
            }
        }
        return true
    }
}

extension Array where Iterator.Element == CGPoint {

    func relation() -> CGFloat? {
        guard count == 4 else {
            return nil
        }

        let firstMiddle = self[0].middle(self[1])
        let secondMiddle = self[1].middle(self[2])
        let thirdMiddle = self[2].middle(self[3])
        let fourthMiddle = self[3].middle(self[0])

        let firstDistance = firstMiddle.distance(thirdMiddle)
        let secondDistance = secondMiddle.distance(fourthMiddle)

        if firstDistance > secondDistance {
            return secondDistance / firstDistance
        }
        return firstDistance / secondDistance

    }

}
